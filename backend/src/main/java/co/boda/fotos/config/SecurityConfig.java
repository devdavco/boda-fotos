package co.boda.fotos.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import co.boda.fotos.error.CodigoError;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Reglas de acceso del API.
 *
 * <ul>
 *   <li>Públicos: el health check del ALB, el estado de la recepción y los endpoints de invitados
 *       ({@code /api/sesiones-subida}, {@code /api/subidas/**}). Los invitados no tienen cuenta:
 *       su identidad es la sesión de subida del encabezado {@code X-Sesion-Subida}, que valida el
 *       servicio, no Spring Security.
 *   <li>{@code POST /api/admin/login} es público; el resto de {@code /api/admin/**} exige sesión
 *       de administrador. El filtro Bearer se agrega en T070; hasta entonces todo eso responde 401.
 *   <li>Cualquier otra ruta se niega.
 * </ul>
 *
 * Las respuestas de seguridad usan el mismo cuerpo JSON {@code {codigo, mensaje}} que el resto
 * del API (contracts/api.md).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                // Cualquier método en las rutas públicas: un método no soportado llega a Spring
                // MVC y responde 405 (lo usa la verificación del rewrite de Amplify).
                .requestMatchers("/api/recepcion/estado").permitAll()
                .requestMatchers("/api/sesiones-subida").permitAll()
                .requestMatchers("/api/subidas/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
                .requestMatchers("/api/admin/**").authenticated()
                // Spring MVC redirige sus errores (405, 404) a /error; si /error está denegado,
                // el cliente ve 403 en vez del código real.
                .requestMatchers("/error").permitAll()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> responder(req, res))
                .accessDeniedHandler((req, res, ex) -> responder(req, res)))
            .cors(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Sin sesión de administrador en {@code /api/admin/**}: 401 {@code NO_AUTENTICADO}. Cualquier
     * otra ruta denegada se presenta como inexistente: 404 {@code NO_ENCONTRADO}.
     */
    private static void responder(HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        boolean admin = req.getRequestURI().startsWith("/api/admin/");
        CodigoError codigo = admin ? CodigoError.NO_AUTENTICADO : CodigoError.NO_ENCONTRADO;
        res.setStatus(codigo.estadoHttp());
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter()
                .write("{\"codigo\":\"" + codigo.name() + "\",\"mensaje\":\""
                        + escapar(codigo.mensaje()) + "\"}");
    }

    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
