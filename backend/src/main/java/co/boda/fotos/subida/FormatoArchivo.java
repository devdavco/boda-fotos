package co.boda.fotos.subida;

import java.util.List;
import java.util.Locale;

import co.boda.fotos.config.BodaProperties.Limites;
import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;

/**
 * Formatos permitidos (FR-010, data-model.md §2). La extensión del nombre decide el formato; un
 * {@code tipoContenido} que la contradice se rechaza. Los límites de tamaño son los de FR-011.
 *
 * <p>Los navegadores de iPhone entregan las fotos HEIC ya convertidas a JPEG y con extensión
 * {@code .jpeg} (research R13), así que el caso {@code .heic} solo llega con archivos sin convertir.
 */
public enum FormatoArchivo {

    JPG("jpg", "image/jpeg", Tipo.FOTO, List.of("jpg", "jpeg"), List.of("image/jpeg", "image/jpg")),
    PNG("png", "image/png", Tipo.FOTO, List.of("png"), List.of("image/png")),
    HEIC("heic", "image/heic", Tipo.FOTO, List.of("heic"), List.of("image/heic", "image/heif")),
    WEBP("webp", "image/webp", Tipo.FOTO, List.of("webp"), List.of("image/webp")),
    MP4("mp4", "video/mp4", Tipo.VIDEO, List.of("mp4"), List.of("video/mp4")),
    MOV("mov", "video/quicktime", Tipo.VIDEO, List.of("mov"), List.of("video/quicktime"));

    /** Cuántos bytes del inicio del objeto hacen falta para comprobar la firma. */
    public static final int BYTES_FIRMA = 16;

    public enum Tipo {
        FOTO,
        VIDEO
    }

    private final String clave;
    private final String contentType;
    private final Tipo tipo;
    private final List<String> extensiones;
    private final List<String> tiposAceptados;

    FormatoArchivo(
            String clave,
            String contentType,
            Tipo tipo,
            List<String> extensiones,
            List<String> tiposAceptados) {
        this.clave = clave;
        this.contentType = contentType;
        this.tipo = tipo;
        this.extensiones = extensiones;
        this.tiposAceptados = tiposAceptados;
    }

    /** Valor del atributo {@code formato} y extensión de la clave en S3: jpg, png, heic, webp, mp4, mov. */
    public String clave() {
        return clave;
    }

    /** Content-Type con el que se firma la URL de subida. */
    public String contentType() {
        return contentType;
    }

    public Tipo tipo() {
        return tipo;
    }

    /**
     * Formato según la extensión del nombre original.
     *
     * @throws ApiException {@code TIPO_NO_PERMITIDO} si no tiene extensión o no es una permitida
     */
    public static FormatoArchivo porNombre(String nombreArchivo) {
        if (nombreArchivo != null) {
            int punto = nombreArchivo.lastIndexOf('.');
            if (punto >= 0 && punto < nombreArchivo.length() - 1) {
                String extension = nombreArchivo.substring(punto + 1).trim().toLowerCase(Locale.ROOT);
                for (FormatoArchivo formato : values()) {
                    if (formato.extensiones.contains(extension)) {
                        return formato;
                    }
                }
            }
        }
        throw new ApiException(CodigoError.TIPO_NO_PERMITIDO);
    }

    /** Formato a partir del valor guardado en el atributo {@code formato} (jpg, png, heic, ...). */
    public static FormatoArchivo porClave(String clave) {
        for (FormatoArchivo formato : values()) {
            if (formato.clave.equals(clave)) {
                return formato;
            }
        }
        throw new IllegalArgumentException("Formato desconocido: " + clave);
    }

    /**
     * Un tipo de contenido ausente o vacío es válido (Android a veces no lo informa para HEIC y MOV).
     *
     * @throws ApiException {@code TIPO_NO_PERMITIDO} si el tipo informado contradice la extensión
     */
    public void validarTipoContenido(String tipoContenido) {
        if (tipoContenido == null || tipoContenido.isBlank()) {
            return;
        }
        String normalizado = tipoContenido.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!tiposAceptados.contains(normalizado)) {
            throw new ApiException(CodigoError.TIPO_NO_PERMITIDO);
        }
    }

    /**
     * Tamaño mínimo y máximo (FR-011).
     *
     * @throws ApiException {@code ARCHIVO_VACIO} si es 0 o negativo; {@code ARCHIVO_MUY_GRANDE}
     *     si excede 40 MB (foto) o 500 MB (video)
     */
    public void validarTamano(long bytes, Limites limites) {
        if (bytes <= 0) {
            throw new ApiException(CodigoError.ARCHIVO_VACIO);
        }
        if (tipo == Tipo.FOTO && bytes > limites.maxFotoBytes()) {
            throw ApiException.fotoMuyGrande();
        }
        if (tipo == Tipo.VIDEO && bytes > limites.maxVideoBytes()) {
            throw ApiException.videoMuyGrande();
        }
    }

    /** Un video de 100 MB o más se sube en partes (FR-008). */
    public boolean usaMultipart(long bytes, Limites limites) {
        return tipo == Tipo.VIDEO && bytes >= limites.umbralMultipartBytes();
    }

    /**
     * Compara el inicio del objeto con la firma del formato: JPEG {@code FF D8 FF}, PNG
     * {@code 89 50 4E 47}, WEBP {@code RIFF....WEBP} y HEIC, MP4 y MOV con la caja {@code ftyp}
     * en los bytes 4 a 7.
     *
     * @param cabecera primeros bytes del objeto (hasta {@value #BYTES_FIRMA})
     */
    public boolean firmaCoincide(byte[] cabecera) {
        return switch (this) {
            case JPG -> empieza(cabecera, 0, 0xFF, 0xD8, 0xFF);
            case PNG -> empieza(cabecera, 0, 0x89, 0x50, 0x4E, 0x47);
            case WEBP -> empieza(cabecera, 0, 'R', 'I', 'F', 'F') && empieza(cabecera, 8, 'W', 'E', 'B', 'P');
            case HEIC, MP4, MOV -> empieza(cabecera, 4, 'f', 't', 'y', 'p');
        };
    }

    private static boolean empieza(byte[] datos, int desde, int... esperado) {
        if (datos == null || datos.length < desde + esperado.length) {
            return false;
        }
        for (int i = 0; i < esperado.length; i++) {
            if ((datos[desde + i] & 0xFF) != esperado[i]) {
                return false;
            }
        }
        return true;
    }
}
