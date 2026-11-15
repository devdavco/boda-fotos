package co.boda.fotos.subida;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import co.boda.fotos.config.BodaProperties;
import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;
import co.boda.fotos.subida.ArchivoMultimedia.Estado;
import co.boda.fotos.subida.ArchivoMultimedia.Modo;

import org.springframework.stereotype.Repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Archivos multimedia en {@code boda-fotos} (data-model.md §2). Todo acceso es por clave
 * ({@code photoId}); no hay {@code Scan}. La galería de los novios se arma con el índice disperso
 * {@code galeria-index}, que solo contiene los archivos {@code COMPLETADO}.
 */
@Repository
public class ArchivoRepository {

    /**
     * Fecha en UTC con milisegundos siempre presentes ({@code 2026-11-21T23:15:04.120Z}). Con un
     * número fijo de decimales, el orden alfabético de {@code galeriaOrden} es el orden
     * cronológico (con {@code Instant.toString()} un instante sin milisegundos ordenaría mal).
     */
    private static final String INDICE_GALERIA = "galeria-index";

    private static final DateTimeFormatter UTC_MILIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final DynamoDbClient dynamo;
    private final BodaProperties propiedades;

    public ArchivoRepository(DynamoDbClient dynamo, BodaProperties propiedades) {
        this.dynamo = dynamo;
        this.propiedades = propiedades;
    }

    /** Guarda un archivo nuevo en estado {@code PENDIENTE}. */
    public void crear(ArchivoMultimedia archivo) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("photoId", AttributeValue.fromS(archivo.photoId()));
        item.put("eventoId", AttributeValue.fromS(archivo.eventoId()));
        item.put("sesionId", AttributeValue.fromS(archivo.sesionId()));
        item.put("nombreInvitado", AttributeValue.fromS(archivo.nombreInvitado()));
        item.put("nombreOriginal", AttributeValue.fromS(archivo.nombreOriginal()));
        item.put("tipo", AttributeValue.fromS(archivo.tipo().name()));
        item.put("formato", AttributeValue.fromS(archivo.formato().clave()));
        item.put("contentType", AttributeValue.fromS(archivo.contentType()));
        item.put("tamano", AttributeValue.fromN(Long.toString(archivo.tamano())));
        item.put("modo", AttributeValue.fromS(archivo.modo().name()));
        item.put("claveS3", AttributeValue.fromS(archivo.claveS3()));
        item.put("estado", AttributeValue.fromS(Estado.PENDIENTE.name()));
        item.put("autorizadoEn", AttributeValue.fromS(UTC_MILIS.format(archivo.autorizadoEn())));
        if (archivo.uploadId() != null) {
            item.put("uploadId", AttributeValue.fromS(archivo.uploadId()));
        }
        if (archivo.numeroPartes() != null) {
            item.put("numeroPartes", AttributeValue.fromN(Integer.toString(archivo.numeroPartes())));
        }
        dynamo.putItem(PutItemRequest.builder()
                .tableName(propiedades.tablas().fotos())
                .item(item)
                .conditionExpression("attribute_not_exists(photoId)")
                .build());
    }

    public Optional<ArchivoMultimedia> buscar(String photoId) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                        .tableName(propiedades.tablas().fotos())
                        .key(Map.of("photoId", AttributeValue.fromS(photoId)))
                        .consistentRead(true)
                        .build())
                .item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(desde(item));
    }

    /**
     * {@code PENDIENTE -> COMPLETADO}: en una sola transacción de dos {@code Update} (sin
     * {@code ConditionCheck}) marca el archivo y suma a los contadores del evento.
     *
     * <ul>
     *   <li>Archivo: condición {@code estado = PENDIENTE}; pone {@code galeriaEvento = eventoId} y
     *       {@code galeriaOrden = <subidoEn>#<photoId>}, con lo que entra al índice de la galería.
     *   <li>Evento: {@code ADD archivosTotal 1} y {@code fotos} o {@code videos}; {@code SET
     *       ultimaSubidaEn}.
     * </ul>
     *
     * Es idempotente: si el archivo ya estaba {@code COMPLETADO} no hace nada y no vuelve a sumar.
     *
     * @throws ApiException {@code NO_ENCONTRADO} si el archivo fue descartado o eliminado
     */
    public void completar(ArchivoMultimedia archivo, Instant subidoEn, String claveMiniatura) {
        String marca = UTC_MILIS.format(subidoEn);
        String contador = archivo.tipo() == FormatoArchivo.Tipo.FOTO ? "fotos" : "videos";

        Map<String, AttributeValue> valoresArchivo = new HashMap<>();
        valoresArchivo.put(":completado", AttributeValue.fromS(Estado.COMPLETADO.name()));
        valoresArchivo.put(":pendiente", AttributeValue.fromS(Estado.PENDIENTE.name()));
        valoresArchivo.put(":subidoEn", AttributeValue.fromS(marca));
        valoresArchivo.put(":evento", AttributeValue.fromS(archivo.eventoId()));
        valoresArchivo.put(":orden", AttributeValue.fromS(marca + "#" + archivo.photoId()));
        String expresionArchivo =
                "SET #estado = :completado, subidoEn = :subidoEn, galeriaEvento = :evento, galeriaOrden = :orden";
        if (claveMiniatura != null) {
            expresionArchivo += ", claveMiniatura = :miniatura";
            valoresArchivo.put(":miniatura", AttributeValue.fromS(claveMiniatura));
        }

        TransactWriteItem actualizarArchivo = TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(propiedades.tablas().fotos())
                        .key(Map.of("photoId", AttributeValue.fromS(archivo.photoId())))
                        .updateExpression(expresionArchivo)
                        .conditionExpression("#estado = :pendiente")
                        .expressionAttributeNames(Map.of("#estado", "estado"))
                        .expressionAttributeValues(valoresArchivo)
                        .build())
                .build();
        TransactWriteItem actualizarEvento = TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(propiedades.tablas().evento())
                        .key(Map.of("eventoId", AttributeValue.fromS(archivo.eventoId())))
                        .updateExpression(
                                "ADD archivosTotal :uno, " + contador + " :uno SET ultimaSubidaEn = :subidoEn")
                        .conditionExpression("attribute_exists(eventoId)")
                        .expressionAttributeValues(Map.of(
                                ":uno", AttributeValue.fromN("1"),
                                ":subidoEn", AttributeValue.fromS(marca)))
                        .build())
                .build();

        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(actualizarArchivo, actualizarEvento)
                    .build());
        } catch (TransactionCanceledException e) {
            // La transacción es atómica: si se canceló no se sumó nada. Si el archivo ya estaba
            // COMPLETADO (reintento de la finalización) es un éxito; en otro caso no existe.
            Optional<ArchivoMultimedia> actual = buscar(archivo.photoId());
            if (actual.isPresent() && actual.get().estado() == Estado.COMPLETADO) {
                return;
            }
            if (actual.isPresent() && actual.get().estado() == Estado.PENDIENTE) {
                throw e; // falló por otra causa (por ejemplo, el ítem del evento no existe)
            }
            throw new ApiException(CodigoError.NO_ENCONTRADO);
        }
    }

    /** Una página de la galería y el {@code galeriaOrden} desde el que sigue, o nulo si no hay más. */
    public record PaginaGaleria(List<ArchivoMultimedia> archivos, String siguienteOrden) {
    }

    /**
     * Archivos {@code COMPLETADO} del evento, del más reciente al más antiguo (FR-026): {@code Query}
     * sobre {@code galeria-index} con {@code ScanIndexForward=false}, sin {@code Scan}.
     *
     * <p>Pide {@code limite + 1} elementos para saber si hay otra página sin que la última página
     * llena devuelva un cursor que lleva a una página vacía.
     *
     * @param desdeOrden {@code galeriaOrden} del último elemento de la página anterior, o nulo
     */
    public PaginaGaleria galeria(String desdeOrden, int limite) {
        QueryRequest.Builder consulta = QueryRequest.builder()
                .tableName(propiedades.tablas().fotos())
                .indexName(INDICE_GALERIA)
                .keyConditionExpression("galeriaEvento = :evento")
                .expressionAttributeValues(Map.of(":evento", AttributeValue.fromS(propiedades.eventId())))
                .scanIndexForward(false)
                .limit(limite + 1);
        if (desdeOrden != null) {
            consulta.exclusiveStartKey(Map.of(
                    "galeriaEvento", AttributeValue.fromS(propiedades.eventId()),
                    "galeriaOrden", AttributeValue.fromS(desdeOrden),
                    "photoId", AttributeValue.fromS(desdeOrden.substring(desdeOrden.indexOf('#') + 1))));
        }
        List<Map<String, AttributeValue>> items = dynamo.query(consulta.build()).items();
        boolean hayMas = items.size() > limite;
        List<Map<String, AttributeValue>> pagina = hayMas ? items.subList(0, limite) : items;
        String siguiente = hayMas ? texto(pagina.get(limite - 1), "galeriaOrden") : null;
        return new PaginaGaleria(pagina.stream().map(ArchivoRepository::desde).toList(), siguiente);
    }

    /** {@code PENDIENTE -> DESCARTADO}. No hace nada si el archivo ya no está pendiente. */
    public void descartar(String photoId) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(propiedades.tablas().fotos())
                    .key(Map.of("photoId", AttributeValue.fromS(photoId)))
                    .updateExpression("SET #estado = :descartado")
                    .conditionExpression("#estado = :pendiente")
                    .expressionAttributeNames(Map.of("#estado", "estado"))
                    .expressionAttributeValues(Map.of(
                            ":descartado", AttributeValue.fromS(Estado.DESCARTADO.name()),
                            ":pendiente", AttributeValue.fromS(Estado.PENDIENTE.name())))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // ya no estaba pendiente: nada que descartar
        }
    }

    private static ArchivoMultimedia desde(Map<String, AttributeValue> item) {
        return new ArchivoMultimedia(
                texto(item, "photoId"),
                texto(item, "eventoId"),
                texto(item, "sesionId"),
                texto(item, "nombreInvitado"),
                texto(item, "nombreOriginal"),
                FormatoArchivo.porClave(texto(item, "formato")),
                Long.parseLong(item.get("tamano").n()),
                Modo.valueOf(texto(item, "modo")),
                opcional(item, "uploadId"),
                item.containsKey("numeroPartes") ? Integer.valueOf(item.get("numeroPartes").n()) : null,
                texto(item, "claveS3"),
                opcional(item, "claveMiniatura"),
                Estado.valueOf(texto(item, "estado")),
                Instant.parse(texto(item, "autorizadoEn")),
                item.containsKey("subidoEn") ? Instant.parse(texto(item, "subidoEn")) : null);
    }

    private static String texto(Map<String, AttributeValue> item, String atributo) {
        AttributeValue valor = item.get(atributo);
        if (valor == null || valor.s() == null) {
            throw new IllegalStateException("Al archivo le falta el atributo '" + atributo + "'");
        }
        return valor.s();
    }

    private static String opcional(Map<String, AttributeValue> item, String atributo) {
        AttributeValue valor = item.get(atributo);
        return valor == null ? null : valor.s();
    }
}
