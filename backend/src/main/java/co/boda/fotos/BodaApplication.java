package co.boda.fotos;

import co.boda.fotos.admin.HashContrasenaCli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BodaApplication {

    public static void main(String[] args) {
        // Modo de línea de comandos (contracts/api.md): genera el hash de una contraseña de
        // administrador sin levantar Spring ni tocar AWS.
        if (args.length > 0 && HashContrasenaCli.COMANDO.equals(args[0])) {
            System.exit(HashContrasenaCli.ejecutar());
            return;
        }
        SpringApplication.run(BodaApplication.class, args);
    }
}
