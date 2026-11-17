package co.boda.fotos.recepcion;

import java.time.Instant;
import java.util.Map;

import co.boda.fotos.config.BodaProperties;

import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Lee el ítem del evento de {@code boda-evento}. Siempre con lectura fuertemente consistente y
 * sin caché: la ventana de recepción y el cierre anticipado se evalúan con el valor actual en
 * cada petición (Principio III), así que editar el ítem en la consola surte efecto al instante.
 */
@Repository
public class EventoRepository {

    private final DynamoDbClient dynamo;
    private final BodaProperties propiedades;

    public EventoRepository(DynamoDbClient dynamo, BodaProperties propiedades) {
        this.dynamo = dynamo;
        this.propiedades = propiedades;
    }

    public Evento obtener() {
        GetItemResponse respuesta = dynamo.getItem(GetItemRequest.builder()
                .tableName(propiedades.tablas().evento())
                .key(Map.of("eventoId", AttributeValue.fromS(propiedades.eventId())))
                .consistentRead(true)
                .build());
        if (!respuesta.hasItem() || respuesta.item().isEmpty()) {
            throw new IllegalStateException("No existe el evento '" + propiedades.eventId()
                    + "' en la tabla " + propiedades.tablas().evento());
        }
        return Evento.desde(respuesta.item());
    }

    /**
     * Registra el cierre anticipado (FR-029) con la condición
     * {@code attribute_not_exists(cierreAnticipadoEn)}: el primer cierre gana y no hay reapertura.
     *
     * @return {@code false} si ya estaba cerrada (el ítem no cambia)
     */
    public boolean cerrarAnticipadamente(Instant en, String usuario) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(propiedades.tablas().evento())
                    .key(Map.of("eventoId", AttributeValue.fromS(propiedades.eventId())))
                    .updateExpression("SET cierreAnticipadoEn = :en, cierreAnticipadoPor = :por")
                    .conditionExpression("attribute_exists(eventoId) AND attribute_not_exists(cierreAnticipadoEn)")
                    .expressionAttributeValues(Map.of(
                            ":en", AttributeValue.fromS(en.toString()),
                            ":por", AttributeValue.fromS(usuario)))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }
}
