package co.boda.fotos.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del backend. Los valores variables vienen de variables de entorno (las mismas
 * que define {@code boda.service} en la user data de la launch template, research R10); las
 * constantes del diseño tienen su valor por defecto aquí y no se repiten en application.yml.
 */
@Validated
@ConfigurationProperties("boda")
public record BodaProperties(
        @NotBlank String awsRegion,
        @NotBlank String s3Bucket,
        @NotBlank String eventId,
        @Valid Tablas tablas,
        @Valid @DefaultValue Limites limites) {

    /** Nombres de las tablas de DynamoDB (variables DYNAMODB_TABLE_*). */
    public record Tablas(
            @NotBlank String fotos,
            @NotBlank String evento,
            @NotBlank String admins,
            @NotBlank String sesiones) {
    }

    /** Límites y vigencias de plan.md, research.md y data-model.md. */
    public record Limites(
            /** URL prefirmada de subida: 5 minutos (o hasta el cierre, lo que ocurra primero). */
            @DefaultValue("5") @Positive int urlSubidaMinutos,
            /** URL prefirmada de lectura (galería, vista, descarga): 15 minutos. */
            @DefaultValue("15") @Positive int urlLecturaMinutos,
            /** Foto máxima: 40 MB = 41.943.040 B. */
            @DefaultValue("41943040") @Positive long maxFotoBytes,
            /** Video máximo: 500 MB = 524.288.000 B. */
            @DefaultValue("524288000") @Positive long maxVideoBytes,
            /** Desde este tamaño un video se sube en multipart: 100 MB = 104.857.600 B. */
            @DefaultValue("104857600") @Positive long umbralMultipartBytes,
            /** Tamaño de cada parte del multipart: 10 MiB = 10.485.760 B. */
            @DefaultValue("10485760") @Positive long parteBytes,
            /** Miniatura máxima: 1 MB = 1.048.576 B. */
            @DefaultValue("1048576") @Positive long miniaturaMaxBytes) {

        public Duration urlSubida() {
            return Duration.ofMinutes(urlSubidaMinutos);
        }

        public Duration urlLectura() {
            return Duration.ofMinutes(urlLecturaMinutos);
        }
    }
}
