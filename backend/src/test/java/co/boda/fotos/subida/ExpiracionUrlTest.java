package co.boda.fotos.subida;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import co.boda.fotos.recepcion.Evento;
import co.boda.fotos.recepcion.VentanaRecepcion;

import org.junit.jupiter.api.Test;

/**
 * Vigencia de las URLs de subida (FR-015, T060): 5 minutos, o menos si el cierre está más cerca,
 * para que ninguna URL sirva después del cierre programado o anticipado. T064.
 */
class ExpiracionUrlTest {

    private static final Duration CINCO_MINUTOS = Duration.ofMinutes(5);
    /** Cierre programado: 2026-11-22 12:00 en Cali = 17:00 UTC. */
    private static final Instant CIERRE = Instant.parse("2026-11-22T17:00:00Z");

    private static Evento evento(Instant cierreAnticipado) {
        return new Evento(
                "boda-2026",
                LocalDateTime.parse("2026-11-19T00:00"),
                LocalDateTime.parse("2026-11-22T12:00"),
                LocalDateTime.parse("2026-12-01T23:59:59"),
                cierreAnticipado,
                cierreAnticipado == null ? null : "mara",
                300,
                5_368_709_120L,
                0,
                0,
                0,
                null);
    }

    @Test
    void lejosDelCierreDura5Minutos() {
        Instant ahora = LocalDateTime.parse("2026-11-21T20:00:00").toInstant(ZoneOffset.UTC);
        assertThat(VentanaRecepcion.vigenciaUrl(evento(null), ahora, CINCO_MINUTOS)).isEqualTo(CINCO_MINUTOS);
    }

    @Test
    void justoA5MinutosDelCierreDura5Minutos() {
        Instant ahora = CIERRE.minus(CINCO_MINUTOS);
        assertThat(VentanaRecepcion.vigenciaUrl(evento(null), ahora, CINCO_MINUTOS)).isEqualTo(CINCO_MINUTOS);
    }

    @Test
    void aMenosDe5MinutosDelCierreSeRecortaHastaElCierre() {
        Instant ahora = CIERRE.minusSeconds(90);
        assertThat(VentanaRecepcion.vigenciaUrl(evento(null), ahora, CINCO_MINUTOS)).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void seRecortaTambienConElCierreAnticipado() {
        Instant anticipado = Instant.parse("2026-11-21T04:00:00Z");
        Instant ahora = anticipado.minusSeconds(30);
        assertThat(VentanaRecepcion.vigenciaUrl(evento(anticipado), ahora, CINCO_MINUTOS)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void conElCierreYaPasadoLaVigenciaNoEsPositiva() {
        // Nunca se llega aquí en el servicio (requireAbierta rechaza antes), y AlmacenamientoS3
        // firma como mínimo 1 segundo; la regla solo garantiza que no se alarga más allá del cierre.
        assertThat(VentanaRecepcion.vigenciaUrl(evento(null), CIERRE.plusSeconds(10), CINCO_MINUTOS).isPositive()).isFalse();
    }
}
