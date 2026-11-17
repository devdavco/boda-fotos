package co.boda.fotos.admin;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import co.boda.fotos.error.CodigoError;
import co.boda.fotos.error.RespuestaJson;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica {@code /api/admin/**} (salvo el login) con {@code Authorization: Bearer <token>}
 * (research R6): SHA-256 del token, búsqueda de la sesión en {@code boda-sesiones}, verificación
 * de {@code expiraEnEpoch} y renovación de la inactividad.
 *
 * <p>Con una sesión válida deja en el contexto de seguridad una autenticación cuyo nombre es el
 * usuario ({@code mara} o {@code daniel}). Sin ella no autentica nada y {@code SecurityConfig}
 * responde 401 {@code NO_AUTENTICADO}.
 *
 * <p>No es un {@code @Component}: lo crea {@code SecurityConfig}, porque Spring Boot registraría
 * un filtro de servlet además del de la cadena de seguridad y correría dos veces.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(BearerTokenFilter.class);
    private static final String PREFIJO = "Bearer ";
    private static final String RUTA_ADMIN = "/api/admin/";
    private static final String RUTA_LOGIN = "/api/admin/login";

    private final SesionAdminRepository sesiones;

    public BearerTokenFilter(SesionAdminRepository sesiones) {
        this.sesiones = sesiones;
    }

    /** Token del encabezado {@code Authorization}, o {@code null} si no es de tipo Bearer. */
    static String token(String autorizacion) {
        if (autorizacion == null || !autorizacion.regionMatches(true, 0, PREFIJO, 0, PREFIJO.length())) {
            return null;
        }
        return autorizacion.substring(PREFIJO.length()).trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String ruta = req.getRequestURI();
        return !ruta.startsWith(RUTA_ADMIN) || ruta.equals(RUTA_LOGIN);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain cadena)
            throws ServletException, IOException {
        String token = token(req.getHeader(HttpHeaders.AUTHORIZATION));
        if (token != null) {
            Optional<SesionAdminRepository.SesionAdmin> sesion;
            try {
                sesion = sesiones.buscar(token);
                sesion.ifPresent(s -> sesiones.renovar(token, s));
            } catch (RuntimeException e) {
                // DynamoDB no respondió: es un error nuestro, no un token inválido
                LOG.error("No se pudo validar la sesión de administrador", e);
                RespuestaJson.escribir(res, CodigoError.ERROR_INTERNO);
                return;
            }
            sesion.ifPresent(s -> autenticar(s.usuario()));
        }
        cadena.doFilter(req, res);
    }

    private static void autenticar(String usuario) {
        UsernamePasswordAuthenticationToken autenticacion = UsernamePasswordAuthenticationToken.authenticated(
                usuario, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(autenticacion);
        SecurityContextHolder.setContext(contexto);
    }
}
