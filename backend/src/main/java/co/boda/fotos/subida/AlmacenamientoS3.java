package co.boda.fotos.subida;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import co.boda.fotos.config.BodaProperties;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

/**
 * Acceso al bucket de medios. Los archivos van directo del navegador a S3 con URLs prefirmadas;
 * el backend solo firma, verifica y borra.
 *
 * <p>Las URLs de subida firman {@code Content-Type} y {@code Content-Length} (el tamaño
 * declarado): S3 rechaza con 403 un cuerpo de otro tamaño desde el primer byte (spike de R2,
 * K9). El navegador no necesita enviar {@code Content-Length} a mano, lo calcula del archivo.
 */
@Component
public class AlmacenamientoS3 {

    /** URL prefirmada y los encabezados que el navegador debe enviar con el {@code PUT}. */
    public record UrlSubida(String url, Map<String, String> encabezados) {
    }

    /** Lo que importa de un objeto existente. */
    public record Objeto(long bytes, String contentType) {
    }

    /** Parte ya subida de un multipart. */
    public record ParteSubida(int numero, String etag) {
    }

    private static final Duration VIGENCIA_MINIMA = Duration.ofSeconds(1);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final BodaProperties propiedades;

    public AlmacenamientoS3(S3Client s3, S3Presigner presigner, BodaProperties propiedades) {
        this.s3 = s3;
        this.presigner = presigner;
        this.propiedades = propiedades;
    }

    // ------------------------------------------------------------------ claves

    /** {@code eventos/<eventoId>/originales/<archivoId>.<ext>}; la genera el sistema (FR-018). */
    public String claveOriginal(String archivoId, FormatoArchivo formato) {
        return "eventos/" + propiedades.eventId() + "/originales/" + archivoId + "." + formato.clave();
    }

    /** {@code eventos/<eventoId>/miniaturas/<archivoId>.jpg}. */
    public String claveMiniatura(String archivoId) {
        return "eventos/" + propiedades.eventId() + "/miniaturas/" + archivoId + ".jpg";
    }

    // ------------------------------------------------------------------ subida

    /** {@code PUT} prefirmado de un objeto completo con su tipo y su tamaño firmados. */
    public UrlSubida urlSubida(String clave, String contentType, long bytes, Duration vigencia) {
        PutObjectRequest peticion = PutObjectRequest.builder()
                .bucket(propiedades.s3Bucket())
                .key(clave)
                .contentType(contentType)
                .contentLength(bytes)
                .build();
        String url = presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(vigenciaValida(vigencia))
                        .putObjectRequest(peticion)
                        .build())
                .url()
                .toString();
        return new UrlSubida(url, Map.of("Content-Type", contentType));
    }

    /** Inicia un multipart y devuelve su {@code uploadId}. */
    public String iniciarMultipart(String clave, String contentType) {
        return s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(propiedades.s3Bucket())
                        .key(clave)
                        .contentType(contentType)
                        .build())
                .uploadId();
    }

    /** Cantidad de partes de un archivo de {@code bytes} con partes de 10 MiB. */
    public int numeroPartes(long bytes) {
        long parte = propiedades.limites().parteBytes();
        return (int) ((bytes + parte - 1) / parte);
    }

    /** Tamaño de la parte {@code numero}: 10 MiB, y la última lleva el resto. */
    public long tamanoParte(long bytes, int numero) {
        long parte = propiedades.limites().parteBytes();
        int total = numeroPartes(bytes);
        return numero < total ? parte : bytes - parte * (total - 1);
    }

    /** {@code UploadPart} prefirmado con el tamaño de la parte firmado. */
    public String urlParte(String clave, String uploadId, int numero, long bytesParte, Duration vigencia) {
        UploadPartRequest peticion = UploadPartRequest.builder()
                .bucket(propiedades.s3Bucket())
                .key(clave)
                .uploadId(uploadId)
                .partNumber(numero)
                .contentLength(bytesParte)
                .build();
        return presigner.presignUploadPart(UploadPartPresignRequest.builder()
                        .signatureDuration(vigenciaValida(vigencia))
                        .uploadPartRequest(peticion)
                        .build())
                .url()
                .toString();
    }

    /** Completa el multipart con las partes en orden. */
    public void completarMultipart(String clave, String uploadId, List<ParteSubida> partes) {
        List<CompletedPart> completadas = new ArrayList<>();
        partes.stream()
                .sorted((a, b) -> Integer.compare(a.numero(), b.numero()))
                .forEach(p -> completadas.add(
                        CompletedPart.builder().partNumber(p.numero()).eTag(p.etag()).build()));
        s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(propiedades.s3Bucket())
                .key(clave)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completadas).build())
                .build());
    }

    public void abortarMultipart(String clave, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(propiedades.s3Bucket())
                    .key(clave)
                    .uploadId(uploadId)
                    .build());
        } catch (S3Exception e) {
            if (!noExiste(e)) {
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ verificación y borrado

    /**
     * Tamaño y tipo del objeto, o vacío si no existe. Sin {@code s3:ListBucket} S3 responde 403 en
     * vez de 404 para una clave inexistente, así que se tratan igual (research R2).
     */
    public Optional<Objeto> inspeccionar(String clave) {
        try {
            HeadObjectResponse r = s3.headObject(HeadObjectRequest.builder()
                    .bucket(propiedades.s3Bucket())
                    .key(clave)
                    .build());
            return Optional.of(new Objeto(r.contentLength(), r.contentType()));
        } catch (S3Exception e) {
            if (noExiste(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Primeros {@link FormatoArchivo#BYTES_FIRMA} bytes del objeto, para comprobar su firma. */
    public byte[] leerCabecera(String clave) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(propiedades.s3Bucket())
                            .key(clave)
                            .range("bytes=0-" + (FormatoArchivo.BYTES_FIRMA - 1))
                            .build())
                    .asByteArray();
        } catch (S3Exception e) {
            if (e.statusCode() == 416 || noExiste(e)) {
                return new byte[0];
            }
            throw e;
        }
    }

    /** Borra un objeto. Borrar uno que no existe no es un error en S3. */
    public void borrar(String clave) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(propiedades.s3Bucket())
                .key(clave)
                .build());
    }

    // ------------------------------------------------------------------ lectura

    /**
     * {@code GET} prefirmado de 15 minutos (galería, vista y descarga).
     *
     * @param contentDisposition si no es nulo, se fuerza en la respuesta (por ejemplo
     *     {@code attachment; filename*=UTF-8''...} para descargar)
     */
    public String urlLectura(String clave, String contentDisposition) {
        GetObjectRequest.Builder peticion =
                GetObjectRequest.builder().bucket(propiedades.s3Bucket()).key(clave);
        if (contentDisposition != null) {
            peticion.responseContentDisposition(contentDisposition);
        }
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(propiedades.limites().urlLectura())
                        .getObjectRequest(peticion.build())
                        .build())
                .url()
                .toString();
    }

    // ------------------------------------------------------------------ utilidades

    /** La vigencia se redondea a segundos: nunca menos de 1 s, aunque falte muy poco para el cierre. */
    private static Duration vigenciaValida(Duration vigencia) {
        return vigencia.compareTo(VIGENCIA_MINIMA) < 0 ? VIGENCIA_MINIMA : vigencia;
    }

    private static boolean noExiste(S3Exception e) {
        return e.statusCode() == 404 || e.statusCode() == 403;
    }
}
