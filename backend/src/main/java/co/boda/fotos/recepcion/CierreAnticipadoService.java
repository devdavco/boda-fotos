package co.boda.fotos.recepcion;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cierre anticipado de la recepción por uno de los novios (FR-029, contracts/api.md
 * {@code POST /api/admin/recepcion/cerrar}). Persiste en {@code boda-evento}, así que sobrevive a
 * reinicios y reemplazos de la instancia, y surte efecto en la siguiente petición porque
 * {@link VentanaRecepcion} lee el ítem sin caché. Es idempotente: cerrar de nuevo responde el
 * cierre que ya existía, con su fecha y su autor.
 */
@Service
public class CierreAnticipadoService {

    private static final Logger LOG = LoggerFactory.getLogger(CierreAnticipadoService.class);

    private final EventoRepository eventos;
    private final VentanaRecepcion ventana;

    public CierreAnticipadoService(EventoRepository eventos, VentanaRecepcion ventana) {
        this.eventos = eventos;
        this.ventana = ventana;
    }

    /** @param usuario el novio que cierra ({@code mara} o {@code daniel}) */
    public Map<String, Object> cerrar(String usuario) {
        if (eventos.cerrarAnticipadamente(ventana.ahora(), usuario)) {
            LOG.warn("Recepción cerrada anticipadamente por {}", usuario);
        }
        Evento evento = eventos.obtener();
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("estado", VentanaRecepcion.calcular(evento, ventana.ahora()).name());
        respuesta.put("cierreEn", VentanaRecepcion.cierreEfectivo(evento).toString());
        respuesta.put("cerradaPor", evento.cierreAnticipadoPor());
        return respuesta;
    }
}
