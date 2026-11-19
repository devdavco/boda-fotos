package co.boda.fotos.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj inyectable: las pruebas lo reemplazan por uno fijo. Siempre UTC. */
@Configuration
public class RelojConfig {

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }
}
