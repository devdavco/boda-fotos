package co.boda.fotos.config;

import java.io.IOException;

import co.boda.fotos.admin.BearerTokenFilter;
import co.boda.fotos.admin.SesionAdminRepository;
import co.boda.fotos.error.CodigoError;
import co.boda.fotos.error.RespuestaJson;

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
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Reglas de acceso del API.
 *
 * <ul>
 *   <li>Públicos: el health check del ALB, el estado de la recepción y los endpoints de invitados
 *       ({@code /api/sesiones-subida}, {@code /api/subidas/**}). Los invitados no tienen cuenta:
 *       su identidad es la sesión de subida del encabezado {@code X-Sesion-Subida}, que valida el
 *       servicio, no Spring Security.
 *   <li>{@code POST /api/admin/login} es público; el resto de {@code /api/admin/**} exige sesión
 *       de administrador: {@link BearerTokenFilter} valida {@code Authorization: Bearer <token>}.
 *   <li>Cualquier otra ruta se niega.
 * </ul>
 *
 * Las respuestas de seguridad usan el mismo cuerpo JSON {@code {codigo, mensaje}} que el resto
 * del API (contracts/api.md).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SesionAdminRepository sesionesAdmin) throws Exception {
        http
            .addFilterBefore(new BearerTokenFilter(sesionesAdmin), AuthorizationFilter.class)
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
        RespuestaJson.escribir(res, admin ? CodigoError.NO_AUTENTICADO : CodigoError.NO_ENCONTRADO);
    }
}
