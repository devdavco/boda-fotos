package co.boda.fotos.recepcion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;

import org.springframework.stereotype.Service;

/**
 * Autoridad sobre la ventana de recepción (Principio III): el navegador solo refleja lo que
 * decide este servicio. Las fechas del ítem del evento están en hora local de Cali y se comparan
 * con la zona IANA {@code America/Bogota}; todo lo demás (reloj, marcas de tiempo) va en UTC.
 *
 * <ul>
 *   <li>{@code NO_INICIADA}: ahora es anterior a la apertura.
 *   <li>{@code CERRADA}: ahora es igual o posterior al cierre, o hay cierre anticipado.
 *   <li>{@code ABIERTA}: en cualquier otro caso. La apertura (00:00) cuenta como abierta y el
 *       cierre (12:00) como cerrada.
 * </ul>
 */
@Service
public class VentanaRecepcion {

    static final ZoneId ZONA = ZoneId.of("America/Bogota");

    private final EventoRepository eventos;
    private final Clock reloj;

    public VentanaRecepcion(EventoRepository eventos, Clock reloj) {
        this.eventos = eventos;
        this.reloj = reloj;
    }

    /**
     * Estado de la ventana tal como lo ve {@code GET /api/recepcion/estado}. {@code cierreEn} es
     * el cierre efectivo (el anticipado si existe y es anterior al programado).
     */
    public record Consulta(EstadoRecepcion estado, Instant aperturaEn, Instant cierreEn, Instant ahora) {
    }

    /** Estado actual, con una lectura fresca del ítem del evento. */
    public EstadoRecepcion estado() {
        return calcular(eventos.obtener(), reloj.instant());
    }

    /**
     * Estado y fechas de la ventana con una sola lectura fresca del ítem del evento y un solo
     * instante del reloj, con la misma regla que {@link #requireAbierta()}.
     */
    public Consulta consultar() {
        Evento evento = eventos.obtener();
        Instant ahora = reloj.instant();
        return new Consulta(calcular(evento, ahora), apertura(evento), cierreEfectivo(evento), ahora);
    }

    /**
     * Exige la recepción abierta antes de emitir cualquier permiso o finalizar una subida.
     *
     * @return el evento leído, para que quien llama use sus topes sin volver a leerlo
     * @throws ApiException {@code RECEPCION_NO_INICIADA} o {@code RECEPCION_CERRADA}
     */
    public Evento requireAbierta() {
        Evento evento = eventos.obtener();
        switch (calcular(evento, reloj.instant())) {
            case NO_INICIADA -> throw new ApiException(CodigoError.RECEPCION_NO_INICIADA);
            case CERRADA -> throw new ApiException(CodigoError.RECEPCION_CERRADA);
            case ABIERTA -> {
                return evento;
            }
        }
        throw new IllegalStateException("Estado de recepción desconocido");
    }

    /** {@code true} si ya pasó el fin del periodo de disponibilidad (FR-032). */
    public boolean periodoTerminado() {
        return periodoTerminado(eventos.obtener(), reloj.instant());
    }

    /** Instante actual del reloj del servicio (UTC). */
    public Instant ahora() {
        return reloj.instant();
    }

    static EstadoRecepcion calcular(Evento evento, Instant ahora) {
        if (ahora.isBefore(apertura(evento))) {
            return EstadoRecepcion.NO_INICIADA;
        }
        if (evento.cierreAnticipadoEn() != null || !ahora.isBefore(cierre(evento))) {
            return EstadoRecepcion.CERRADA;
        }
        return EstadoRecepcion.ABIERTA;
    }

    static boolean periodoTerminado(Evento evento, Instant ahora) {
        return ahora.isAfter(aInstante(evento.disponibleHastaLocal()));
    }

    public static Instant apertura(Evento evento) {
        return aInstante(evento.aperturaLocal());
    }

    /** Cierre programado, sin tener en cuenta el anticipado. */
    public static Instant cierre(Evento evento) {
        return aInstante(evento.cierreLocal());
    }

    /** Cierre efectivo: el anticipado si existe y es anterior al programado (FR-015, FR-029). */
    public static Instant cierreEfectivo(Evento evento) {
        Instant programado = cierre(evento);
        Instant anticipado = evento.cierreAnticipadoEn();
        return anticipado != null && anticipado.isBefore(programado) ? anticipado : programado;
    }

    private static Instant aInstante(LocalDateTime local) {
        return local.atZone(ZONA).toInstant();
    }
}
