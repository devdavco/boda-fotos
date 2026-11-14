package co.boda.fotos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BodaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BodaApplication.class, args);
    }
}
