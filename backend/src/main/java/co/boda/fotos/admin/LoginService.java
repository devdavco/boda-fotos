package co.boda.fotos.admin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Inicio de sesión de los novios (FR-024, FR-025, research R6).
 *
 * <ul>
 *   <li>Contraseñas con bcrypt de costo 12.
 *   <li>Todos los fallos responden el mismo {@code CREDENCIALES_INVALIDAS}: usuario inexistente,
 *       contraseña mala o cuenta bloqueada. Para un usuario inexistente se compara contra un hash
 *       ficticio, así el tiempo de respuesta es parecido al de una contraseña mala.
 *   <li>5 fallos consecutivos bloquean la cuenta 15 minutos. Mientras está bloqueada no se
 *       comprueba la contraseña (se compara contra el hash ficticio solo para igualar el tiempo).
 *   <li>Un login correcto reinicia los fallos y emite un token de 32 bytes aleatorios en
 *       Base64URL; en DynamoDB se guarda solo su SHA-256.
 * </ul>
 */
@Service
public class LoginService {

    private static final Logger LOG = LoggerFactory.getLogger(LoginService.class);

    static final int COSTO_BCRYPT = 12;
    static final int MAX_FALLOS = 5;
    static final Duration BLOQUEO = Duration.ofMinutes(15);
    /** bcrypt solo usa los primeros 72 bytes, y Spring Security rechaza contraseñas más largas. */
    static final int MAX_BYTES_CONTRASENA = 72;

    /** Resultado de un login correcto. */
    public record Sesion(String token, String usuario, String nombreVisible) {
    }

    private final AdminRepository admins;
    private final SesionAdminRepository sesiones;
    private final Clock reloj;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(COSTO_BCRYPT);
    private final SecureRandom azar = new SecureRandom();
    /** Hash de una contraseña aleatoria: se compara contra él cuando el usuario no existe. */
    private final String hashFicticio;

    public LoginService(AdminRepository admins, SesionAdminRepository sesiones, Clock reloj) {
        this.admins = admins;
        this.sesiones = sesiones;
        this.reloj = reloj;
        byte[] aleatoria = new byte[16];
        azar.nextBytes(aleatoria);
        this.hashFicticio = bcrypt.encode(Base64.getEncoder().encodeToString(aleatoria));
    }

    /**
     * @throws ApiException {@code CREDENCIALES_INVALIDAS} en cualquier fallo
     */
    public Sesion entrar(String usuarioEscrito, String contrasena) {
        String usuario = usuarioEscrito == null ? "" : usuarioEscrito.trim().toLowerCase(Locale.ROOT);
        String clave = contrasena == null ? "" : contrasena;
        boolean claveValida = !clave.isEmpty()
                && clave.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES_CONTRASENA;

        Optional<Administrador> encontrado = usuario.isEmpty() ? Optional.empty() : admins.buscar(usuario);
        if (encontrado.isEmpty()) {
            comparar(claveValida ? clave : "x", hashFicticio);
            throw credencialesInvalidas();
        }

        Administrador admin = encontrado.get();
        Instant ahora = reloj.instant();
        // Bloqueada o con una contraseña que bcrypt no acepta: no se compara con el hash real, pero
        // sí con el ficticio, para que el tiempo no delate que la cuenta existe.
        if (admin.bloqueada(ahora)) {
            comparar("x", hashFicticio);
            throw credencialesInvalidas();
        }
        if (!claveValida) {
            comparar("x", hashFicticio);
            registrarFallo(admin.usuario(), ahora);
            throw credencialesInvalidas();
        }
        if (!comparar(clave, admin.hashContrasena())) {
            registrarFallo(admin.usuario(), ahora);
            throw credencialesInvalidas();
        }

        if (admin.intentosFallidos() > 0 || admin.bloqueadaHasta() != null) {
            admins.reiniciarFallos(admin.usuario());
        }
        String token = nuevoToken();
        sesiones.crear(token, admin.usuario());
        LOG.info("Inicio de sesión de {}", admin.usuario());
        return new Sesion(token, admin.usuario(), admin.nombreVisible());
    }

    private void registrarFallo(String usuario, Instant ahora) {
        int fallos = admins.sumarFallo(usuario);
        if (fallos >= MAX_FALLOS) {
            admins.bloquear(usuario, ahora.plus(BLOQUEO));
            LOG.warn("Cuenta {} bloqueada {} minutos tras {} fallos", usuario, BLOQUEO.toMinutes(), fallos);
        }
    }

    /** Un hash mal formado en la tabla cuenta como contraseña incorrecta, no como error 500. */
    private boolean comparar(String clave, String hash) {
        try {
            return bcrypt.matches(clave, hash);
        } catch (IllegalArgumentException e) {
            LOG.error("No se pudo comparar la contraseña: {}", e.getMessage());
            return false;
        }
    }

    private String nuevoToken() {
        byte[] bytes = new byte[32];
        azar.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException credencialesInvalidas() {
        return new ApiException(CodigoError.CREDENCIALES_INVALIDAS);
    }
}
