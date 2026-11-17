package co.boda.fotos.admin;

import java.io.Console;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * {@code java -jar app.jar hash-contrasena} (FR-024, FR-031, research R7): pide la contraseña dos
 * veces sin eco y escribe en la salida estándar el hash bcrypt de costo 12 ({@code $2a$12$...})
 * para pegarlo en el {@code put-item} de {@code boda-admins} desde CloudShell. No levanta el
 * servidor web ni toca AWS.
 */
public final class HashContrasenaCli {

    public static final String COMANDO = "hash-contrasena";

    /** Mínimo razonable para una cuenta que da acceso a todas las fotos. */
    static final int MIN_CARACTERES = 10;

    private HashContrasenaCli() {
    }

    /** @return el código de salida del proceso */
    public static int ejecutar() {
        Console consola = System.console();
        // Desde Java 22 System.console() existe aunque la entrada esté redirigida: se exige terminal
        // para que la contraseña nunca venga de un archivo ni quede en el historial.
        if (consola == null || !consola.isTerminal()) {
            System.err.println("Este comando necesita una terminal interactiva para leer la contraseña sin eco.");
            return 2;
        }
        char[] primera = consola.readPassword("Contraseña: ");
        char[] segunda = consola.readPassword("Repite la contraseña: ");
        try {
            if (primera == null || segunda == null) {
                System.err.println("No se leyó ninguna contraseña.");
                return 1;
            }
            if (!Arrays.equals(primera, segunda)) {
                System.err.println("Las contraseñas no coinciden.");
                return 1;
            }
            if (primera.length < MIN_CARACTERES) {
                System.err.println("La contraseña debe tener al menos " + MIN_CARACTERES + " caracteres.");
                return 1;
            }
            if (StandardCharsets.UTF_8.encode(CharBuffer.wrap(primera)).remaining()
                    > LoginService.MAX_BYTES_CONTRASENA) {
                System.err.println("La contraseña no puede superar " + LoginService.MAX_BYTES_CONTRASENA
                        + " bytes (bcrypt ignora el resto).");
                return 1;
            }
            String hash = new BCryptPasswordEncoder(LoginService.COSTO_BCRYPT).encode(new String(primera));
            System.out.println(hash);
            return 0;
        } finally {
            if (primera != null) {
                Arrays.fill(primera, '\0');
            }
            if (segunda != null) {
                Arrays.fill(segunda, '\0');
            }
        }
    }
}
