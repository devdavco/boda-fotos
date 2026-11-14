package co.boda.fotos.subida;

import java.time.Duration;
import java.time.Instant;

import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;
import co.boda.fotos.recepcion.Evento;
import co.boda.fotos.recepcion.VentanaRecepcion;

import org.springframework.stereotype.Service;

/** Crea y valida las sesiones de subida de los invitados (FR-004a, FR-012a). */
@Service
public class SesionSubidaService {

    /** Respuesta de {@code POST /api/sesiones-subida}. */
    public record SesionCreada(String sesionId, String nombre, long maxArchivos, long maxBytes) {
    }

    private final SesionSubidaRepository sesiones;
    private final VentanaRecepcion ventana;

    public SesionSubidaService(SesionSubidaRepository sesiones, VentanaRecepcion ventana) {
        this.sesiones = sesiones;
        this.ventana = ventana;
    }

    /**
     * Crea una sesión nueva. Solo con la recepción abierta. La sesión vence un día después del
     * cierre programado de la recepción.
     */
    public SesionCreada crear(String nombre) {
        Evento evento = ventana.requireAbierta();
        String nombreValido = NombreInvitado.validar(nombre);
        Instant expiraEn = VentanaRecepcion.cierre(evento).plus(Duration.ofDays(1));
        SesionSubida sesion = sesiones.crear(nombreValido, expiraEn);
        return new SesionCreada(
                sesion.token(), nombreValido, evento.maxArchivosPorSesion(), evento.maxBytesPorSesion());
    }

    /**
     * Sesión del encabezado {@code X-Sesion-Subida}.
     *
     * @throws ApiException {@code SESION_INVALIDA} si falta, no existe o venció
     */
    public SesionSubida requerir(String token) {
        return sesiones.buscar(token).orElseThrow(() -> new ApiException(CodigoError.SESION_INVALIDA));
    }
}
