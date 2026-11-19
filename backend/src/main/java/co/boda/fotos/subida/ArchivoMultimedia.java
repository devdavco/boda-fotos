package co.boda.fotos.subida;

import java.time.Instant;

/**
 * Foto o video del evento: ítem de {@code boda-fotos} (data-model.md §2). {@code photoId} es el
 * {@code archivoId} del API, un UUID v4 generado por el backend.
 *
 * @param nombreInvitado nombre declarado por el invitado (1 a 60 caracteres tras recortar espacios,
 *     sin caracteres de control; FR-004). No es una identidad verificada.
 * @param nombreOriginal nombre del archivo en el celular, hasta 255 caracteres; solo informativo
 * @param tamano bytes declarados, verificados con {@code HeadObject} al completar
 * @param uploadId solo en {@code MULTIPART}
 * @param numeroPartes solo en {@code MULTIPART}
 * @param claveMiniatura solo si la miniatura llegó y se verificó
 * @param subidoEn fecha de subida de la galería (al completar)
 */
public record ArchivoMultimedia(
        String photoId,
        String eventoId,
        String sesionId,
        String nombreInvitado,
        String nombreOriginal,
        FormatoArchivo formato,
        long tamano,
        Modo modo,
        String uploadId,
        Integer numeroPartes,
        String claveS3,
        String claveMiniatura,
        Estado estado,
        Instant autorizadoEn,
        Instant subidoEn) {

    public enum Modo {
        SIMPLE,
        MULTIPART
    }

    /**
     * Ciclo de vida: {@code PENDIENTE -> COMPLETADO -> ELIMINADO}, o {@code PENDIENTE ->
     * DESCARTADO} si se cancela o se rechaza al completar.
     */
    public enum Estado {
        PENDIENTE,
        COMPLETADO,
        DESCARTADO,
        ELIMINADO
    }

    public FormatoArchivo.Tipo tipo() {
        return formato.tipo();
    }

    public String contentType() {
        return formato.contentType();
    }
}
