package co.boda.fotos.admin;

import java.time.Instant;

/**
 * Cuenta de uno de los novios: ítem de {@code boda-admins} (data-model.md §3).
 *
 * @param usuario en minúsculas ({@code mara} o {@code daniel})
 * @param hashContrasena bcrypt de costo 12; nunca la contraseña (FR-024)
 * @param bloqueadaHasta UTC; presente tras 5 fallos consecutivos (FR-025)
 */
public record Administrador(
        String usuario,
        String nombreVisible,
        String hashContrasena,
        int intentosFallidos,
        Instant bloqueadaHasta) {

    public boolean bloqueada(Instant ahora) {
        return bloqueadaHasta != null && bloqueadaHasta.isAfter(ahora);
    }
}
