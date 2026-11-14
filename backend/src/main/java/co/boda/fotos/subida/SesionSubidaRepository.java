package co.boda.fotos.subida;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import co.boda.fotos.config.BodaProperties;
import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;

import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Sesiones de subida en {@code boda-sesiones} (data-model.md §4). El identificador es de 128 bits
 * aleatorios en Base64URL emitido por el servidor; la clave del ítem lleva el prefijo
 * {@code sub#}, así que un identificador enviado por el navegador nunca puede apuntar a una sesión
 * de administrador ({@code adm#}). El tope de FR-012a se aplica con un {@code UpdateItem}
 * condicional atómico: es exacto aunque lleguen peticiones simultáneas.
 */
@Repository
public class SesionSubidaRepository {

    private static final String PREFIJO = "sub#";
    /** 16 bytes en Base64URL sin relleno = 22 caracteres. */
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{22}$");

    private final DynamoDbClient dynamo;
    private final BodaProperties propiedades;
    private final Clock reloj;
    private final SecureRandom azar = new SecureRandom();

    public SesionSubidaRepository(DynamoDbClient dynamo, BodaProperties propiedades, Clock reloj) {
        this.dynamo = dynamo;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    /** Crea una sesión nueva con los contadores en cero. */
    public SesionSubida crear(String nombre, Instant expiraEn) {
        byte[] bytes = new byte[16];
        azar.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant ahora = reloj.instant();
        long expiraEnEpoch = expiraEn.getEpochSecond();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.fromS(PREFIJO + token));
        item.put("tipo", AttributeValue.fromS("SUBIDA"));
        item.put("eventoId", AttributeValue.fromS(propiedades.eventId()));
        item.put("ultimoNombre", AttributeValue.fromS(nombre));
        item.put("archivos", AttributeValue.fromN("0"));
        item.put("bytes", AttributeValue.fromN("0"));
        item.put("creadaEn", AttributeValue.fromS(ahora.toString()));
        item.put("expiraEnEpoch", AttributeValue.fromN(Long.toString(expiraEnEpoch)));

        dynamo.putItem(PutItemRequest.builder()
                .tableName(propiedades.tablas().sesiones())
                .item(item)
                .conditionExpression("attribute_not_exists(id)")
                .build());
        return new SesionSubida(token, propiedades.eventId(), nombre, 0, 0, ahora, expiraEnEpoch);
    }

    /**
     * Busca la sesión. Es vacío si el identificador no tiene el formato esperado, no existe, es de
     * otro evento o ya venció (el TTL de DynamoDB borra con retraso, así que se comprueba aquí).
     */
    public Optional<SesionSubida> buscar(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                        .tableName(propiedades.tablas().sesiones())
                        .key(Map.of("id", AttributeValue.fromS(PREFIJO + token)))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()
                || !"SUBIDA".equals(valor(item, "tipo"))
                || !propiedades.eventId().equals(valor(item, "eventoId"))) {
            return Optional.empty();
        }
        long expiraEnEpoch = Long.parseLong(item.get("expiraEnEpoch").n());
        if (expiraEnEpoch <= reloj.instant().getEpochSecond()) {
            return Optional.empty();
        }
        return Optional.of(new SesionSubida(
                token,
                valor(item, "eventoId"),
                valor(item, "ultimoNombre"),
                Long.parseLong(item.get("archivos").n()),
                Long.parseLong(item.get("bytes").n()),
                Instant.parse(valor(item, "creadaEn")),
                expiraEnEpoch));
    }

    /** Guarda el último nombre declarado (el invitado puede cambiarlo entre archivos). */
    public void actualizarNombre(String token, String nombre) {
        dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(propiedades.tablas().sesiones())
                .key(Map.of("id", AttributeValue.fromS(PREFIJO + token)))
                .updateExpression("SET ultimoNombre = :nombre")
                .conditionExpression("attribute_exists(id)")
                .expressionAttributeValues(Map.of(":nombre", AttributeValue.fromS(nombre)))
                .build());
    }

    /**
     * Cuenta un archivo y sus bytes contra el tope de la sesión (FR-012a), de forma atómica:
     * {@code ADD archivos 1, bytes :tamano} con la condición {@code archivos < :maxArchivos AND
     * bytes <= :maxBytes - :tamano}.
     *
     * @throws ApiException {@code TOPE_SESION} si se alcanzó el tope; {@code SESION_INVALIDA} si la
     *     sesión ya no existe
     */
    public void reservar(String token, long tamano, long maxArchivos, long maxBytes) {
        Map<String, AttributeValue> valores = Map.of(
                ":uno", AttributeValue.fromN("1"),
                ":tamano", AttributeValue.fromN(Long.toString(tamano)),
                ":maxArchivos", AttributeValue.fromN(Long.toString(maxArchivos)),
                ":maxBytesMenosTamano", AttributeValue.fromN(Long.toString(maxBytes - tamano)));
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(propiedades.tablas().sesiones())
                    .key(Map.of("id", AttributeValue.fromS(PREFIJO + token)))
                    .updateExpression("ADD #archivos :uno, #bytes :tamano")
                    .conditionExpression(
                            "attribute_exists(#id) AND #archivos < :maxArchivos AND #bytes <= :maxBytesMenosTamano")
                    .expressionAttributeNames(Map.of("#id", "id", "#archivos", "archivos", "#bytes", "bytes"))
                    .expressionAttributeValues(valores)
                    .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                    .build());
        } catch (ConditionalCheckFailedException e) {
            boolean existia = e.hasItem() && !e.item().isEmpty();
            throw new ApiException(existia ? CodigoError.TOPE_SESION : CodigoError.SESION_INVALIDA);
        }
    }

    private static String valor(Map<String, AttributeValue> item, String atributo) {
        AttributeValue v = item.get(atributo);
        return v == null ? null : v.s();
    }
}
