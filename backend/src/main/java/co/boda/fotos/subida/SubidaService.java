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
import co.boda.fotos.subida.AlmacenamientoS3.UrlSubida;
import co.boda.fotos.subida.ArchivoMultimedia.Estado;
import co.boda.fotos.subida.ArchivoMultimedia.Modo;

import org.springframework.stereotype.Service;

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

        Duration vigencia = limites.urlSubida();
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
        ventana.requireAbierta();
        if (archivo.modo() != Modo.SIMPLE) {
            throw new ApiException(CodigoError.PETICION_INVALIDA);
        }
        Duration vigencia = propiedades.limites().urlSubida();
        UrlSubida url =
                almacenamiento.urlSubida(archivo.claveS3(), archivo.contentType(), archivo.tamano(), vigencia);
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("archivoId", archivo.photoId());
        respuesta.put("modo", archivo.modo().name());
        respuesta.put("url", url.url());
        respuesta.put("encabezados", url.encabezados());
        respuesta.put("expiraEn", ventana.ahora().plus(vigencia).toString());
        return respuesta;
    }

    /**
     * URLs prefirmadas de partes de un multipart. El navegador las pide justo antes de enviar cada
     * parte, como máximo {@value #MAX_PARTES_POR_PETICION} por petición (research R2).
     */
    public Map<String, Object> partes(String token, String archivoId, List<Integer> numeros) {
        ArchivoMultimedia archivo = pendienteDeLaSesion(token, archivoId);
        ventana.requireAbierta();
        if (archivo.modo() != Modo.MULTIPART
                || numeros == null
                || numeros.isEmpty()
                || numeros.size() > MAX_PARTES_POR_PETICION
                || numeros.stream().anyMatch(n -> n == null || n < 1 || n > archivo.numeroPartes())) {
            throw new ApiException(CodigoError.PETICION_INVALIDA);
        }
        Duration vigencia = propiedades.limites().urlSubida();
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
        respuesta.put("expiraEn", ventana.ahora().plus(vigencia).toString());
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
