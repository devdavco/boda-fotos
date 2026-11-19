package co.boda.fotos.subida;

import java.time.Instant;

/**
 * Sesión de subida de un invitado (data-model.md §4): agrupa los archivos enviados desde un mismo
 * navegador y lleva los contadores que aplican el tope de FR-012a. {@code token} es el
 * identificador que conoce el navegador (header {@code X-Sesion-Subida}); en DynamoDB el ítem
 * se guarda como {@code sub#<token>}.
 */
public record SesionSubida(
        String token,
        String eventoId,
        String ultimoNombre,
        long archivos,
        long bytes,
        Instant creadaEn,
        long expiraEnEpoch) {
}
