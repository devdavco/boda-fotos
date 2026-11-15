package co.boda.fotos.admin;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import co.boda.fotos.config.BodaProperties;

import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Cuentas de los novios en {@code boda-admins} (data-model.md §3). Las cuentas se crean y se
 * restablecen desde CloudShell (research R7): el backend solo lee y actualiza
 * {@code intentosFallidos} y {@code bloqueadaHasta}, y nunca escribe {@code hashContrasena}.
 *
 * <p>Todos los {@code UpdateItem} llevan la condición {@code attribute_exists(usuario)}: sin ella,
 * un login con un usuario inexistente crearía un ítem nuevo en la tabla.
 */
@Repository
public class AdminRepository {

    private final DynamoDbClient dynamo;
    private final BodaProperties propiedades;

    public AdminRepository(DynamoDbClient dynamo, BodaProperties propiedades) {
        this.dynamo = dynamo;
        this.propiedades = propiedades;
    }

    public Optional<Administrador> buscar(String usuario) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                        .tableName(propiedades.tablas().admins())
                        .key(clave(usuario))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Administrador(
                texto(item, "usuario"),
                texto(item, "nombreVisible"),
                texto(item, "hashContrasena"),
                item.containsKey("intentosFallidos") ? Integer.parseInt(item.get("intentosFallidos").n()) : 0,
                item.containsKey("bloqueadaHasta") ? Instant.parse(item.get("bloqueadaHasta").s()) : null));
    }

    /**
     * Suma un fallo de forma atómica ({@code ADD}) y devuelve el total de fallos consecutivos.
     * Con lecturas y escrituras separadas, dos intentos simultáneos podrían contar uno solo.
     *
     * @return los fallos consecutivos tras sumar este, o 0 si la cuenta ya no existe
     */
    public int sumarFallo(String usuario) {
        try {
            Map<String, AttributeValue> nuevos = dynamo.updateItem(UpdateItemRequest.builder()
                            .tableName(propiedades.tablas().admins())
                            .key(clave(usuario))
                            .updateExpression("ADD intentosFallidos :uno")
                            .conditionExpression("attribute_exists(usuario)")
                            .expressionAttributeValues(Map.of(":uno", AttributeValue.fromN("1")))
                            .returnValues(ReturnValue.UPDATED_NEW)
                            .build())
                    .attributes();
            return Integer.parseInt(nuevos.get("intentosFallidos").n());
        } catch (ConditionalCheckFailedException e) {
            return 0;
        }
    }

    /**
     * Bloquea la cuenta hasta {@code hasta} (FR-025) y pone el contador en 0, de modo que al
     * terminar el bloqueo hay otros 5 intentos.
     */
    public void bloquear(String usuario, Instant hasta) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(propiedades.tablas().admins())
                    .key(clave(usuario))
                    .updateExpression("SET bloqueadaHasta = :hasta, intentosFallidos = :cero")
                    .conditionExpression("attribute_exists(usuario)")
                    .expressionAttributeValues(Map.of(
                            ":hasta", AttributeValue.fromS(hasta.toString()),
                            ":cero", AttributeValue.fromN("0")))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // la cuenta ya no existe: nada que bloquear
        }
    }

    /** Login correcto: reinicia los fallos y quita un bloqueo vencido. */
    public void reiniciarFallos(String usuario) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(propiedades.tablas().admins())
                    .key(clave(usuario))
                    .updateExpression("SET intentosFallidos = :cero REMOVE bloqueadaHasta")
                    .conditionExpression("attribute_exists(usuario)")
                    .expressionAttributeValues(Map.of(":cero", AttributeValue.fromN("0")))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // la cuenta se borró entre la lectura y la escritura
        }
    }

    private static Map<String, AttributeValue> clave(String usuario) {
        return Map.of("usuario", AttributeValue.fromS(usuario));
    }

    private static String texto(Map<String, AttributeValue> item, String atributo) {
        AttributeValue valor = item.get(atributo);
        if (valor == null || valor.s() == null) {
            throw new IllegalStateException("A la cuenta le falta el atributo '" + atributo + "'");
        }
        return valor.s();
    }
}
