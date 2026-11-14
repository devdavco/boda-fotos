package co.boda.fotos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Clientes del AWS SDK for Java v2. No se configuran credenciales: se usa la cadena por defecto
 * del SDK (el rol de la instancia EC2 en AWS, el perfil de AWS CLI en local). Nunca access keys
 * en el repositorio. Spring cierra los clientes al apagarse (son AutoCloseable).
 */
@Configuration
public class AwsConfig {

    @Bean
    S3Client s3Client(BodaProperties propiedades) {
        return S3Client.builder().region(Region.of(propiedades.awsRegion())).build();
    }

    @Bean
    S3Presigner s3Presigner(BodaProperties propiedades) {
        return S3Presigner.builder().region(Region.of(propiedades.awsRegion())).build();
    }

    @Bean
    DynamoDbClient dynamoDbClient(BodaProperties propiedades) {
        return DynamoDbClient.builder().region(Region.of(propiedades.awsRegion())).build();
    }
}
