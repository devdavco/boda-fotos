package co.boda.fotos.recepcion;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Ítem del evento en {@code boda-evento} (data-model.md §1). Las fechas de apertura, cierre y fin
 * de disponibilidad están en hora local de Cali, sin zona ({@code 2026-11-19T00:00}); el cierre
 * anticipado está en UTC. {@link VentanaRecepcion} las convierte con {@code America/Bogota}.
 */
public record Evento(
        String eventoId,
        LocalDateTime aperturaLocal,
        LocalDateTime cierreLocal,
        LocalDateTime disponibleHastaLocal,
        Instant cierreAnticipadoEn,
        String cierreAnticipadoPor,
        long maxArchivosPorSesion,
        long maxBytesPorSesion,
        long archivosTotal,
        long fotos,
        long videos,
        Instant ultimaSubidaEn) {

    /** Convierte el ítem de DynamoDB; un atributo obligatorio ausente es un error de configuración. */
    static Evento desde(Map<String, AttributeValue> item) {
        return new Evento(
                texto(item, "eventoId"),
                LocalDateTime.parse(texto(item, "aperturaLocal")),
                LocalDateTime.parse(texto(item, "cierreLocal")),
                LocalDateTime.parse(texto(item, "disponibleHastaLocal")),
                instante(item, "cierreAnticipadoEn"),
                textoOpcional(item, "cierreAnticipadoPor"),
                numero(item, "maxArchivosPorSesion"),
                numero(item, "maxBytesPorSesion"),
                numeroOpcional(item, "archivosTotal"),
                numeroOpcional(item, "fotos"),
                numeroOpcional(item, "videos"),
                instante(item, "ultimaSubidaEn"));
    }

    private static String texto(Map<String, AttributeValue> item, String atributo) {
        String valor = textoOpcional(item, atributo);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "Al ítem del evento le falta el atributo obligatorio '" + atributo + "'");
        }
        return valor;
    }

    private static String textoOpcional(Map<String, AttributeValue> item, String atributo) {
        AttributeValue valor = item.get(atributo);
        return valor == null ? null : valor.s();
    }

    private static Instant instante(Map<String, AttributeValue> item, String atributo) {
        String valor = textoOpcional(item, atributo);
        return valor == null || valor.isBlank() ? null : Instant.parse(valor);
    }

    private static long numero(Map<String, AttributeValue> item, String atributo) {
        AttributeValue valor = item.get(atributo);
        if (valor == null || valor.n() == null) {
            throw new IllegalStateException(
                    "Al ítem del evento le falta el atributo numérico obligatorio '" + atributo + "'");
        }
        return Long.parseLong(valor.n());
    }

    private static long numeroOpcional(Map<String, AttributeValue> item, String atributo) {
        AttributeValue valor = item.get(atributo);
        return valor == null || valor.n() == null ? 0L : Long.parseLong(valor.n());
    }
}
