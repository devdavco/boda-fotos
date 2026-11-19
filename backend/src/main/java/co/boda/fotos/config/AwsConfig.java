package co.boda.fotos.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Clientes del AWS SDK for Java v2. No se configuran credenciales: se usa la cadena por defecto
 * del SDK (el rol de la instancia EC2 en AWS, el perfil de AWS CLI en local). Nunca access keys
 * en el repositorio. Spring cierra los clientes al apagarse (son AutoCloseable).
 *
 * <p>Solo para desarrollo local ({@code local/README.md}): si {@code S3_ENDPOINT} tiene valor (por
 * ejemplo {@code http://localhost:9090}, un S3 falso), el cliente y el presigner usan ese endpoint
 * con rutas de estilo path. En AWS la variable no existe y no cambia nada.
 */
@Configuration
public class AwsConfig {

    private final String endpointS3Local;

    AwsConfig(@Value("${boda.s3-endpoint:}") String endpointS3Local) {
        this.endpointS3Local = endpointS3Local == null ? "" : endpointS3Local.trim();
    }

    @Bean
    S3Client s3Client(BodaProperties propiedades) {
        var builder = S3Client.builder().region(Region.of(propiedades.awsRegion()));
        if (!endpointS3Local.isEmpty()) {
            builder.endpointOverride(URI.create(endpointS3Local)).forcePathStyle(true);
        }
        return builder.build();
    }

    /**
     * Para {@code us-east-1} el SDK firmaría contra el endpoint global ({@code
     * bucket.s3.amazonaws.com}). Se fuerza el endpoint regional ({@code
     * bucket.s3.us-east-1.amazonaws.com}), que es el que usó el spike de R2 y el que define
     * research.md.
     */
    @Bean
    S3Presigner s3Presigner(BodaProperties propiedades) {
        if (!endpointS3Local.isEmpty()) {
            return S3Presigner.builder()
                    .region(Region.of(propiedades.awsRegion()))
                    .endpointOverride(URI.create(endpointS3Local))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .build();
        }
        return S3Presigner.builder()
                .region(Region.of(propiedades.awsRegion()))
                .endpointOverride(URI.create("https://s3." + propiedades.awsRegion() + ".amazonaws.com"))
                .build();
    }

    @Bean
    DynamoDbClient dynamoDbClient(BodaProperties propiedades) {
        return DynamoDbClient.builder().region(Region.of(propiedades.awsRegion())).build();
    }
}
