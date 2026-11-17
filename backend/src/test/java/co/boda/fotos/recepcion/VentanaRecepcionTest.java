package co.boda.fotos.recepcion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;

import org.junit.jupiter.api.Test;

/**
 * Ventana de recepción con reloj fijo (FR-013 a FR-017, FR-029, FR-032). Las fechas del evento son
 * de Cali (UTC−5, sin horario de verano): 2026-11-19 00:00 en Cali es 05:00 UTC. T063.
 */
class VentanaRecepcionTest {

    private static Evento evento(Instant cierreAnticipado) {
        return new Evento(
                "boda-2026",
                LocalDateTime.parse("2026-11-19T00:00"),
                LocalDateTime.parse("2026-11-22T12:00"),
                LocalDateTime.parse("2026-12-01T23:59:59"),
                cierreAnticipado,
                cierreAnticipado == null ? null : "daniel",
                300,
                5_368_709_120L,
                0,
                0,
                0,
                null);
    }

    private static final Evento NORMAL = evento(null);

    private static Instant utc(String fechaHora) {
        return LocalDateTime.parse(fechaHora).toInstant(ZoneOffset.UTC);
    }

    @Test
    void antesDeLaAperturaNoHaIniciado() {
        assertThat(VentanaRecepcion.calcular(NORMAL, utc("2026-11-19T04:59:59"))).isEqualTo(EstadoRecepcion.NO_INICIADA);
    }

    @Test
    void justoALas0000DeCaliEstaAbierta() {
        assertThat(VentanaRecepcion.calcular(NORMAL, utc("2026-11-19T05:00:00"))).isEqualTo(EstadoRecepcion.ABIERTA);
        assertThat(VentanaRecepcion.apertura(NORMAL)).isEqualTo(utc("2026-11-19T05:00:00"));
    }

    @Test
    void unSegundoAntesDeLas1200SigueAbiertaYALas1200Cierra() {
        assertThat(VentanaRecepcion.calcular(NORMAL, utc("2026-11-22T16:59:59"))).isEqualTo(EstadoRecepcion.ABIERTA);
        assertThat(VentanaRecepcion.calcular(NORMAL, utc("2026-11-22T17:00:00"))).isEqualTo(EstadoRecepcion.CERRADA);
    }

    @Test
    void despuesDelCierreEstaCerrada() {
        assertThat(VentanaRecepcion.calcular(NORMAL, utc("2026-11-25T12:00:00"))).isEqualTo(EstadoRecepcion.CERRADA);
    }

    @Test
    void elCierreAnticipadoCierraYAdelantaElCierreEfectivo() {
        Instant anticipado = utc("2026-11-21T04:02:00");
        Evento cerrado = evento(anticipado);
        assertThat(VentanaRecepcion.calcular(cerrado, utc("2026-11-21T05:00:00"))).isEqualTo(EstadoRecepcion.CERRADA);
        assertThat(VentanaRecepcion.cierreEfectivo(cerrado)).isEqualTo(anticipado);
        assertThat(VentanaRecepcion.cierreEfectivo(NORMAL)).isEqualTo(utc("2026-11-22T17:00:00"));
    }

    @Test
    void elPeriodoTerminaDespuesDel1DeDiciembreA235959() {
        assertThat(VentanaRecepcion.periodoTerminado(NORMAL, utc("2026-12-02T04:59:59"))).isFalse();
        assertThat(VentanaRecepcion.periodoTerminado(NORMAL, utc("2026-12-02T05:00:00"))).isTrue();
        assertThat(VentanaRecepcion.disponibleHasta(NORMAL)).isEqualTo(utc("2026-12-02T04:59:59"));
    }

    @Test
    void requireAbiertaUsaLaLecturaDelEventoYElReloj() {
        EventoRepository repo = mock(EventoRepository.class);
        when(repo.obtener()).thenReturn(NORMAL);

        VentanaRecepcion antes = new VentanaRecepcion(repo, Clock.fixed(utc("2026-11-18T12:00:00"), ZoneOffset.UTC));
        assertThatThrownBy(antes::requireAbierta)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).codigo())
                .isEqualTo(CodigoError.RECEPCION_NO_INICIADA);

        VentanaRecepcion durante = new VentanaRecepcion(repo, Clock.fixed(utc("2026-11-20T12:00:00"), ZoneOffset.UTC));
        assertThat(durante.requireAbierta()).isSameAs(NORMAL);

        VentanaRecepcion despues = new VentanaRecepcion(repo, Clock.fixed(utc("2026-11-23T00:00:00"), ZoneOffset.UTC));
        assertThatThrownBy(despues::requireAbierta)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).codigo())
                .isEqualTo(CodigoError.RECEPCION_CERRADA);
    }
}
