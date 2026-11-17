package co.boda.fotos.galeria;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;
import co.boda.fotos.recepcion.Evento;
import co.boda.fotos.recepcion.EventoRepository;
import co.boda.fotos.recepcion.VentanaRecepcion;
import co.boda.fotos.subida.AlmacenamientoS3;
import co.boda.fotos.subida.ArchivoMultimedia;
import co.boda.fotos.subida.ArchivoRepository;

import org.springframework.stereotype.Service;

/**
 * Resumen y galería de los novios (contracts/api.md: {@code GET /api/admin/resumen} y
 * {@code GET /api/admin/galeria}; FR-026, FR-030b).
 *
 * <p>El cursor de la galería es el {@code galeriaOrden} del último elemento en Base64URL. Como
 * {@code galeriaOrden = <subidoEn>#<photoId>}, con él se reconstruye la clave completa del índice
 * sin exponer nada más; un cursor que no tiene esa forma se rechaza con {@code PETICION_INVALIDA}.
 */
@Service
public class GaleriaService {

    static final int LIMITE_POR_DEFECTO = 24;
    static final int LIMITE_MAXIMO = 48;

    private static final Pattern ORDEN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z#[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final EventoRepository eventos;
    private final ArchivoRepository archivos;
    private final AlmacenamientoS3 almacenamiento;
    private final VentanaRecepcion ventana;

    public GaleriaService(
            EventoRepository eventos,
            ArchivoRepository archivos,
            AlmacenamientoS3 almacenamiento,
            VentanaRecepcion ventana) {
        this.eventos = eventos;
        this.archivos = archivos;
        this.almacenamiento = almacenamiento;
        this.ventana = ventana;
    }

    /** Contadores y estado para la cabecera. Funciona también después del fin de disponibilidad. */
    public Map<String, Object> resumen() {
        Evento evento = eventos.obtener();
        Instant ahora = ventana.ahora();

        Map<String, Object> recepcion = new LinkedHashMap<>();
        recepcion.put("estado", VentanaRecepcion.calcular(evento, ahora).name());
        recepcion.put("cierreEn", VentanaRecepcion.cierreEfectivo(evento).toString());

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("archivosTotal", evento.archivosTotal());
        respuesta.put("fotos", evento.fotos());
        respuesta.put("videos", evento.videos());
        respuesta.put("ultimaSubidaEn", evento.ultimaSubidaEn() == null ? null : evento.ultimaSubidaEn().toString());
        respuesta.put("recepcion", recepcion);
        respuesta.put("disponibleHasta", VentanaRecepcion.disponibleHasta(evento).toString());
        respuesta.put("periodoTerminado", VentanaRecepcion.periodoTerminado(evento, ahora));
        return respuesta;
    }

    /**
     * @param cursor el {@code siguienteCursor} de la página anterior, o nulo para la primera
     * @param limiteTexto de 1 a 48; nulo o vacío es 24
     * @throws ApiException {@code PERIODO_TERMINADO} después del 2026-12-01 23:59:59 de Cali
     */
    public Map<String, Object> galeria(String cursor, String limiteTexto) {
        exigirPeriodoVigente(ventana);
        int limite = limite(limiteTexto);
        ArchivoRepository.PaginaGaleria pagina = archivos.galeria(decodificar(cursor), limite);

        List<Map<String, Object>> elementos = new ArrayList<>();
        for (ArchivoMultimedia archivo : pagina.archivos()) {
            elementos.add(elemento(archivo));
        }
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("elementos", elementos);
        respuesta.put("siguienteCursor", codificar(pagina.siguienteOrden()));
        return respuesta;
    }

    private Map<String, Object> elemento(ArchivoMultimedia archivo) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("archivoId", archivo.photoId());
        e.put("tipo", archivo.tipo().name());
        e.put("formato", archivo.formato().clave());
        e.put("nombreInvitado", archivo.nombreInvitado());
        e.put("nombreOriginal", archivo.nombreOriginal());
        e.put("tamano", archivo.tamano());
        e.put("subidoEn", archivo.subidoEn() == null ? null : archivo.subidoEn().toString());
        e.put("urlMiniatura",
                archivo.claveMiniatura() == null ? null : almacenamiento.urlLectura(archivo.claveMiniatura(), null));
        return e;
    }

    /**
     * Después del fin de disponibilidad no se entregan ni la galería ni URLs de archivos (FR-032).
     * No se borra nada: el material sigue en S3 hasta que los novios lo pidan (FR-033).
     */
    static void exigirPeriodoVigente(VentanaRecepcion ventana) {
        if (ventana.periodoTerminado()) {
            throw new ApiException(CodigoError.PERIODO_TERMINADO);
        }
    }

    static int limite(String texto) {
        if (texto == null || texto.isBlank()) {
            return LIMITE_POR_DEFECTO;
        }
        try {
            int limite = Integer.parseInt(texto.trim());
            if (limite >= 1 && limite <= LIMITE_MAXIMO) {
                return limite;
            }
        } catch (NumberFormatException e) {
            // cae al error de abajo
        }
        throw new ApiException(CodigoError.PETICION_INVALIDA);
    }

    static String decodificar(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String orden = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            if (ORDEN.matcher(orden).matches()) {
                return orden;
            }
        } catch (IllegalArgumentException e) {
            // Base64 inválido: cae al error de abajo
        }
        throw new ApiException(CodigoError.PETICION_INVALIDA);
    }

    static String codificar(String orden) {
        return orden == null
                ? null
                : Base64.getUrlEncoder().withoutPadding().encodeToString(orden.getBytes(StandardCharsets.UTF_8));
    }
}
