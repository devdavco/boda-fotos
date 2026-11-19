package co.boda.fotos.subida;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import co.boda.fotos.config.BodaProperties;
import co.boda.fotos.config.BodaProperties.Limites;
import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;
import co.boda.fotos.recepcion.Evento;
import co.boda.fotos.recepcion.VentanaRecepcion;
import co.boda.fotos.subida.AlmacenamientoS3.Objeto;
import co.boda.fotos.subida.AlmacenamientoS3.ParteSubida;
import co.boda.fotos.subida.AlmacenamientoS3.UrlSubida;
import co.boda.fotos.subida.ArchivoMultimedia.Estado;
import co.boda.fotos.subida.ArchivoMultimedia.Modo;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Autoriza las subidas de los invitados (Principio III: el servidor es la autoridad). Antes de
 * emitir cualquier URL valida sesión, ventana de recepción, nombre, formato, tamaño y tope de la
 * sesión; el navegador solo recibe permisos para exactamente lo que se validó.
 */
@Service
public class SubidaService {

    /** Máximo de URLs de partes por petición: las que salen en paralelo (research R2). */
    static final int MAX_PARTES_POR_PETICION = 3;

    private static final int MAX_NOMBRE_ORIGINAL = 255;
    private static final String TIPO_MINIATURA = "image/jpeg";

    /**
     * Cuerpo de {@code POST /api/subidas}.
     *
     * @param tamanoMiniatura bytes de la miniatura que el navegador ya generó; la URL de la
     *     miniatura firma ese tamaño, así que hace falta cuando {@code conMiniatura} es verdadero
     */
    public record PeticionSubida(
            String nombreInvitado,
            String nombreArchivo,
            String tipoContenido,
            Long tamano,
            Boolean conMiniatura,
            Long tamanoMiniatura) {
    }

    /** Parte de un multipart ya subida, con el {@code ETag} que devolvió S3. */
    public record ParteCompletada(Integer numero, String etag) {
    }

    /** Cuerpo de {@code POST /api/subidas/{archivoId}/completar}. */
    public record PeticionCompletar(List<ParteCompletada> partes, Boolean miniaturaSubida) {
    }

    private final SesionSubidaService sesiones;
    private final SesionSubidaRepository sesionesRepo;
    private final ArchivoRepository archivos;
    private final AlmacenamientoS3 almacenamiento;
    private final VentanaRecepcion ventana;
    private final BodaProperties propiedades;

    public SubidaService(
            SesionSubidaService sesiones,
            SesionSubidaRepository sesionesRepo,
            ArchivoRepository archivos,
            AlmacenamientoS3 almacenamiento,
            VentanaRecepcion ventana,
            BodaProperties propiedades) {
        this.sesiones = sesiones;
        this.sesionesRepo = sesionesRepo;
        this.archivos = archivos;
        this.almacenamiento = almacenamiento;
        this.ventana = ventana;
        this.propiedades = propiedades;
    }

    /**
     * Autoriza un archivo. Valida en este orden: sesión, recepción abierta, nombre, formato por
     * extensión, tamaño y tope de la sesión. Crea el ítem {@code PENDIENTE} y devuelve la URL
     * firmada (o los datos del multipart).
     */
    public Map<String, Object> autorizar(String token, PeticionSubida peticion) {
        Limites limites = propiedades.limites();
        SesionSubida sesion = sesiones.requerir(token);
        Evento evento = ventana.requireAbierta();
        String nombre = NombreInvitado.validar(peticion.nombreInvitado());
        if (peticion.nombreArchivo() == null || peticion.nombreArchivo().isBlank() || peticion.tamano() == null) {
            throw new ApiException(CodigoError.PETICION_INVALIDA);
        }
        FormatoArchivo formato = FormatoArchivo.porNombre(peticion.nombreArchivo());
        formato.validarTipoContenido(peticion.tipoContenido());
        long tamano = peticion.tamano();
        formato.validarTamano(tamano, limites);

        sesionesRepo.reservar(
                sesion.token(), tamano, evento.maxArchivosPorSesion(), evento.maxBytesPorSesion());
        sesionesRepo.actualizarNombre(sesion.token(), nombre);

        String archivoId = UUID.randomUUID().toString();
        String clave = almacenamiento.claveOriginal(archivoId, formato);
        Modo modo = formato.usaMultipart(tamano, limites) ? Modo.MULTIPART : Modo.SIMPLE;
        String uploadId = null;
        Integer numeroPartes = null;
        if (modo == Modo.MULTIPART) {
            uploadId = almacenamiento.iniciarMultipart(clave, formato.contentType());
            numeroPartes = almacenamiento.numeroPartes(tamano);
        }

        Instant ahora = ventana.ahora();
        try {
            archivos.crear(new ArchivoMultimedia(
                    archivoId,
                    propiedades.eventId(),
                    sesion.token(),
                    nombre,
                    recortar(peticion.nombreArchivo().strip(), MAX_NOMBRE_ORIGINAL),
                    formato,
                    tamano,
                    modo,
                    uploadId,
                    numeroPartes,
                    clave,
                    null,
                    Estado.PENDIENTE,
                    ahora,
                    null));
        } catch (RuntimeException e) {
            if (uploadId != null) {
                almacenamiento.abortarMultipart(clave, uploadId);
            }
            throw e;
        }

        Duration vigencia = VentanaRecepcion.vigenciaUrl(evento, ahora, limites.urlSubida());
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("archivoId", archivoId);
        respuesta.put("modo", modo.name());
        if (modo == Modo.SIMPLE) {
            UrlSubida url = almacenamiento.urlSubida(clave, formato.contentType(), tamano, vigencia);
            respuesta.put("url", url.url());
            respuesta.put("encabezados", url.encabezados());
        } else {
            respuesta.put("tamanoParte", limites.parteBytes());
            respuesta.put("numeroPartes", numeroPartes);
        }
        if (quiereMiniatura(peticion, limites)) {
            respuesta.put(
                    "urlMiniatura",
                    almacenamiento
                            .urlSubida(
                                    almacenamiento.claveMiniatura(archivoId),
                                    TIPO_MINIATURA,
                                    peticion.tamanoMiniatura(),
                                    vigencia)
                            .url());
        }
        respuesta.put("expiraEn", ahora.plus(vigencia).toString());
        return respuesta;
    }

    /** Nueva URL para reintentar un archivo simple ya autorizado. No suma al tope (FR-007). */
    public Map<String, Object> nuevaUrl(String token, String archivoId) {
        ArchivoMultimedia archivo = pendienteDeLaSesion(token, archivoId);
        Evento evento = ventana.requireAbierta();
        if (archivo.modo() != Modo.SIMPLE) {
            throw new ApiException(CodigoError.PETICION_INVALIDA);
        }
        Instant ahora = ventana.ahora();
        Duration vigencia = VentanaRecepcion.vigenciaUrl(evento, ahora, propiedades.limites().urlSubida());
        UrlSubida url =
                almacenamiento.urlSubida(archivo.claveS3(), archivo.contentType(), archivo.tamano(), vigencia);
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("archivoId", archivo.photoId());
        respuesta.put("modo", archivo.modo().name());
        respuesta.put("url", url.url());
        respuesta.put("encabezados", url.encabezados());
        respuesta.put("expiraEn", ahora.plus(vigencia).toString());
        return respuesta;
    }

    /**
     * URLs prefirmadas de partes de un multipart. El navegador las pide justo antes de enviar cada
     * parte, como máximo {@value #MAX_PARTES_POR_PETICION} por petición (research R2).
     */
    public Map<String, Object> partes(String token, String archivoId, List<Integer> numeros) {
        ArchivoMultimedia archivo = pendienteDeLaSesion(token, archivoId);
        Evento evento = ventana.requireAbierta();
        if (archivo.modo() != Modo.MULTIPART
                || numeros == null
                || numeros.isEmpty()
                || numeros.size() > MAX_PARTES_POR_PETICION
                || numeros.stream().anyMatch(n -> n == null || n < 1 || n > archivo.numeroPartes())) {
            throw new ApiException(CodigoError.PETICION_INVALIDA);
        }
        Instant ahora = ventana.ahora();
        Duration vigencia = VentanaRecepcion.vigenciaUrl(evento, ahora, propiedades.limites().urlSubida());
        List<Map<String, Object>> urls = new ArrayList<>();
        for (int numero : new LinkedHashSet<>(numeros)) {
            Map<String, Object> parte = new LinkedHashMap<>();
            parte.put("numero", numero);
            parte.put(
                    "url",
                    almacenamiento.urlParte(
                            archivo.claveS3(),
                            archivo.uploadId(),
                            numero,
                            almacenamiento.tamanoParte(archivo.tamano(), numero),
                            vigencia));
            urls.add(parte);
        }
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("urls", urls);
        respuesta.put("expiraEn", ahora.plus(vigencia).toString());
        return respuesta;
    }

    /**
     * Cierra la subida de un archivo (FR-016): exige la recepción abierta, completa el multipart si
     * corresponde, verifica en S3 el tamaño, el tipo y la firma de bytes de lo que llegó y recién
     * entonces lo pasa a {@code COMPLETADO} (entra a la galería). Es idempotente.
     *
     * @throws ApiException {@code RECEPCION_CERRADA} si la recepción ya cerró, después de borrar lo
     *     que haya llegado y descartar el archivo; {@code SUBIDA_INCOMPLETA} (409, reintentable) si el objeto o alguna
     *     parte no está en S3; {@code CONTENIDO_INCONSISTENTE} (422) si lo subido no coincide con
     *     lo autorizado, en cuyo caso se borra el objeto
     */
    public Map<String, Object> completar(String token, String archivoId, PeticionCompletar peticion) {
        SesionSubida sesion = sesiones.requerir(token);
        ArchivoMultimedia archivo = archivos.buscar(archivoId)
                .filter(a -> a.sesionId().equals(sesion.token()))
                .orElseThrow(() -> new ApiException(CodigoError.NO_ENCONTRADO));
        if (archivo.estado() == Estado.COMPLETADO) {
            return resultado(archivo);
        }
        if (archivo.estado() != Estado.PENDIENTE) {
            throw new ApiException(CodigoError.NO_ENCONTRADO);
        }
        try {
            ventana.requireAbierta();
        } catch (ApiException e) {
            // Recepción cerrada (programada o anticipada): lo que llegó después del cierre no entra
            // a la galería, en ninguno de los dos modos (FR-016, escenario 2 de la US4). Se borra el
            // objeto o se aborta el multipart y el archivo queda DESCARTADO.
            if (e.codigo() == CodigoError.RECEPCION_CERRADA) {
                descartarSubida(archivo);
            }
            throw e;
        }

        if (archivo.modo() == Modo.MULTIPART) {
            completarMultipart(archivo, peticion);
        }

        Objeto objeto = almacenamiento
                .inspeccionar(archivo.claveS3())
                .orElseThrow(() -> new ApiException(CodigoError.SUBIDA_INCOMPLETA));
        if (objeto.bytes() != archivo.tamano()
                || objeto.contentType() == null
                || !objeto.contentType().equalsIgnoreCase(archivo.contentType())
                || !archivo.formato().firmaCoincide(almacenamiento.leerCabecera(archivo.claveS3()))) {
            rechazarContenido(archivo);
        }

        boolean miniaturaSubida = peticion != null && Boolean.TRUE.equals(peticion.miniaturaSubida());
        String claveMiniatura = miniaturaSubida ? verificarMiniatura(archivo) : null;
        archivos.completar(archivo, ventana.ahora(), claveMiniatura);
        return resultado(archivo);
    }

    /**
     * El invitado quita de su lista un archivo pendiente: aborta el multipart o borra el objeto
     * (y su miniatura) y marca el archivo {@code DESCARTADO}. El cupo de la sesión no se devuelve.
     */
    public void cancelar(String token, String archivoId) {
        descartarSubida(pendienteDeLaSesion(token, archivoId));
    }

    /**
     * Quita lo que haya llegado de un archivo pendiente (aborta el multipart o borra el objeto, y la
     * miniatura) y lo marca {@code DESCARTADO}. El cupo de la sesión no se devuelve (FR-012a).
     */
    private void descartarSubida(ArchivoMultimedia archivo) {
        if (archivo.modo() == Modo.MULTIPART) {
            almacenamiento.abortarMultipart(archivo.claveS3(), archivo.uploadId());
        }
        // También en multipart: si un completar anterior ya ensambló el objeto (y falló después,
        // por ejemplo en la verificación), abortar no lo borra y quedaría huérfano en S3.
        almacenamiento.borrar(archivo.claveS3());
        almacenamiento.borrar(almacenamiento.claveMiniatura(archivo.photoId()));
        archivos.descartar(archivo.photoId());
    }

    private void completarMultipart(ArchivoMultimedia archivo, PeticionCompletar peticion) {
        List<ParteCompletada> partes = peticion == null ? null : peticion.partes();
        int esperadas = archivo.numeroPartes();
        boolean validas = partes != null
                && partes.size() == esperadas
                && partes.stream().allMatch(p -> p != null && p.numero() != null && p.etag() != null && !p.etag().isBlank())
                && partes.stream().map(ParteCompletada::numero).distinct().count() == esperadas
                && partes.stream().allMatch(p -> p.numero() >= 1 && p.numero() <= esperadas);
        if (!validas) {
            throw new ApiException(CodigoError.PETICION_INVALIDA);
        }
        try {
            almacenamiento.completarMultipart(
                    archivo.claveS3(),
                    archivo.uploadId(),
                    partes.stream().map(p -> new ParteSubida(p.numero(), p.etag())).toList());
        } catch (S3Exception e) {
            String codigo = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
            switch (codigo) {
                // NoSuchUpload: el multipart ya se completó en un intento anterior; la
                // verificación del objeto decide si todo está bien.
                case "NoSuchUpload" -> { }
                case "InvalidPart", "InvalidPartOrder", "EntityTooSmall" ->
                        throw new ApiException(CodigoError.SUBIDA_INCOMPLETA);
                default -> throw e;
            }
        }
    }

    /** Borra lo subido y descarta el archivo: no coincide con lo que se autorizó. */
    private void rechazarContenido(ArchivoMultimedia archivo) {
        almacenamiento.borrar(archivo.claveS3());
        almacenamiento.borrar(almacenamiento.claveMiniatura(archivo.photoId()));
        archivos.descartar(archivo.photoId());
        throw new ApiException(CodigoError.CONTENIDO_INCONSISTENTE);
    }

    /**
     * La miniatura nunca hace fallar la subida: si no está, pesa más de 1 MB, no es JPEG o su firma
     * no coincide, se borra y el archivo queda sin miniatura.
     *
     * @return la clave de la miniatura verificada, o {@code null}
     */
    private String verificarMiniatura(ArchivoMultimedia archivo) {
        String clave = almacenamiento.claveMiniatura(archivo.photoId());
        Objeto objeto = almacenamiento.inspeccionar(clave).orElse(null);
        if (objeto == null) {
            return null;
        }
        boolean valida = objeto.bytes() >= 1
                && objeto.bytes() <= propiedades.limites().miniaturaMaxBytes()
                && TIPO_MINIATURA.equalsIgnoreCase(objeto.contentType())
                && FormatoArchivo.JPG.firmaCoincide(almacenamiento.leerCabecera(clave));
        if (!valida) {
            almacenamiento.borrar(clave);
            return null;
        }
        return clave;
    }

    private static Map<String, Object> resultado(ArchivoMultimedia archivo) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("archivoId", archivo.photoId());
        respuesta.put("estado", Estado.COMPLETADO.name());
        return respuesta;
    }

    /**
     * Archivo {@code PENDIENTE} de la sesión del encabezado. Un archivo de otra sesión, inexistente
     * o que ya no está pendiente se presenta como {@code NO_ENCONTRADO}.
     */
    ArchivoMultimedia pendienteDeLaSesion(String token, String archivoId) {
        SesionSubida sesion = sesiones.requerir(token);
        return archivos.buscar(archivoId)
                .filter(a -> a.sesionId().equals(sesion.token()) && a.estado() == Estado.PENDIENTE)
                .orElseThrow(() -> new ApiException(CodigoError.NO_ENCONTRADO));
    }

    private static boolean quiereMiniatura(PeticionSubida peticion, Limites limites) {
        return Boolean.TRUE.equals(peticion.conMiniatura())
                && peticion.tamanoMiniatura() != null
                && peticion.tamanoMiniatura() >= 1
                && peticion.tamanoMiniatura() <= limites.miniaturaMaxBytes();
    }

    /** Recorta a un máximo de caracteres (puntos de código, para no partir un emoji). */
    private static String recortar(String texto, int maximo) {
        return texto.codePointCount(0, texto.length()) <= maximo
                ? texto
                : texto.substring(0, texto.offsetByCodePoints(0, maximo));
    }
}
