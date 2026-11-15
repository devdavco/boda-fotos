package co.boda.fotos.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import co.boda.fotos.config.BodaProperties;

import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Sesiones de los novios en {@code boda-sesiones} (data-model.md §4, research R6). La clave del
 * ítem es {@code adm#<SHA-256 en hex del token>}: el token en claro nunca se guarda, así que leer
 * la tabla no permite suplantar a nadie. El prefijo {@code adm#} separa estas sesiones de las de
 * subida ({@code sub#}).
 *
 * <p>La expiración es por inactividad: {@code expiraEnEpoch = ultimoUsoEn + 8 h}. El backend la
 * comprueba en cada petición, porque el TTL de DynamoDB borra con hasta 48 h de retraso.
 */
@Repository
public class SesionAdminRepository {

    /** Inactividad máxima (FR-030). */
    public static final Duration INACTIVIDAD_MAXIMA = Duration.ofHours(8);
    /** {@code ultimoUsoEn} se escribe como máximo cada 5 minutos, para no escribir en cada petición. */
    static final Duration RENOVACION_MINIMA = Duration.ofMinutes(5);

    private static final String PREFIJO = "adm#";
    private static final String TIPO = "ADMIN";
    /** 32 bytes en Base64URL sin relleno = 43 caracteres. */
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final DynamoDbClient dynamo;
    private final BodaProperties propiedades;
    private final Clock reloj;

    public SesionAdminRepository(DynamoDbClient dynamo, BodaProperties propiedades, Clock reloj) {
        this.dynamo = dynamo;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    /** Sesión válida de un administrador. */
    public record SesionAdmin(String usuario, Instant ultimoUsoEn, long expiraEnEpoch) {
    }

    /** Guarda la sesión de un token recién emitido. */
    public void crear(String token, String usuario) {
        Instant ahora = reloj.instant();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.fromS(id(token)));
        item.put("tipo", AttributeValue.fromS(TIPO));
        item.put("usuario", AttributeValue.fromS(usuario));
        item.put("creadaEn", AttributeValue.fromS(ahora.toString()));
        item.put("ultimoUsoEn", AttributeValue.fromS(ahora.toString()));
        item.put("expiraEnEpoch", AttributeValue.fromN(Long.toString(expiraEn(ahora))));
        dynamo.putItem(PutItemRequest.builder()
                .tableName(propiedades.tablas().sesiones())
                .item(item)
                .conditionExpression("attribute_not_exists(id)")
                .build());
    }

    /**
     * Busca la sesión del token. Es vacío si el token no tiene el formato esperado, no existe, no
     * es de administrador o venció por inactividad.
     */
    public Optional<SesionAdmin> buscar(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                        .tableName(propiedades.tablas().sesiones())
                        .key(Map.of("id", AttributeValue.fromS(id(token))))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty() || !TIPO.equals(texto(item, "tipo"))) {
            return Optional.empty();
        }
        long expiraEnEpoch = Long.parseLong(item.get("expiraEnEpoch").n());
        if (expiraEnEpoch <= reloj.instant().getEpochSecond()) {
            return Optional.empty();
        }
        return Optional.of(new SesionAdmin(
                texto(item, "usuario"), Instant.parse(texto(item, "ultimoUsoEn")), expiraEnEpoch));
    }

    /**
     * Extiende la inactividad desde ahora si el último registro tiene más de 5 minutos. La
     * condición {@code attribute_exists(id)} evita recrear una sesión que se cerró mientras tanto.
     */
    public void renovar(String token, SesionAdmin sesion) {
        Instant ahora = reloj.instant();
        if (Duration.between(sesion.ultimoUsoEn(), ahora).compareTo(RENOVACION_MINIMA) < 0) {
            return;
        }
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(propiedades.tablas().sesiones())
                    .key(Map.of("id", AttributeValue.fromS(id(token))))
                    .updateExpression("SET ultimoUsoEn = :ahora, expiraEnEpoch = :expira")
                    .conditionExpression("attribute_exists(id)")
                    .expressionAttributeValues(Map.of(
                            ":ahora", AttributeValue.fromS(ahora.toString()),
                            ":expira", AttributeValue.fromN(Long.toString(expiraEn(ahora)))))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // la sesión se cerró entre la lectura y la renovación
        }
    }

    /** Cierra la sesión (FR-030). Borrar una que no existe no es un error. */
    public void borrar(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return;
        }
        dynamo.deleteItem(DeleteItemRequest.builder()
                .tableName(propiedades.tablas().sesiones())
                .key(Map.of("id", AttributeValue.fromS(id(token))))
                .build());
    }

    private static long expiraEn(Instant ultimoUso) {
        return ultimoUso.plus(INACTIVIDAD_MAXIMA).getEpochSecond();
    }

    private static String id(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return PREFIJO + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible", e);
        }
    }

    private static String texto(Map<String, AttributeValue> item, String atributo) {
        AttributeValue valor = item.get(atributo);
        return valor == null ? null : valor.s();
    }
}
