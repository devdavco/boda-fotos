package co.boda.fotos.recepcion;

import java.util.Map;

import co.boda.fotos.config.BodaProperties;

import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

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
}
