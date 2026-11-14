package co.boda.fotos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Esqueleto de seguridad. El starter de seguridad bloquea todo por defecto (incluido
 * /actuator/health, que el ALB necesita para dar por sana a la instancia), así que se
 * permiten solo estas rutas y se niega el resto. Se amplía en T036.
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
                // Cualquier método: así un POST/DELETE llega a Spring MVC y responde 405,
                // lo que sirve para el spike del rewrite de Amplify.
                .requestMatchers("/api/recepcion/estado").permitAll()
                // Spring MVC redirige sus errores (405, 404) a /error; si /error está denegado,
                // el cliente ve 403 en vez del código real.
                .requestMatchers("/error").permitAll()
                .anyRequest().denyAll())
            .cors(Customizer.withDefaults());
        return http.build();
    }
}
