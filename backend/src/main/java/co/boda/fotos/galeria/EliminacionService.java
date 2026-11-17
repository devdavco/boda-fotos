package co.boda.fotos.galeria;

import co.boda.fotos.config.BodaProperties;
import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;
import co.boda.fotos.recepcion.VentanaRecepcion;
import co.boda.fotos.subida.AlmacenamientoS3;
import co.boda.fotos.subida.ArchivoMultimedia;
import co.boda.fotos.subida.ArchivoRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Eliminación definitiva de un archivo por uno de los novios (FR-028, data-model.md §2). No hay
 * papelera ni vuelta atrás; la confirmación la pide el front.
 *
 * <p>Primero se borran los objetos de S3 y después se hace la transacción. Así un fallo a mitad
 * de camino se resuelve repitiendo la petición: borrar un objeto que ya no existe no es un error,
 * y la transacción solo resta de los contadores una vez. En el orden inverso, un fallo dejaría el
 * original en S3 aunque el archivo ya no apareciera en la galería.
 */
@Service
public class EliminacionService {

    private static final Logger LOG = LoggerFactory.getLogger(EliminacionService.class);

    private final ArchivoRepository archivos;
    private final AlmacenamientoS3 almacenamiento;
    private final VentanaRecepcion ventana;
    private final BodaProperties propiedades;

    public EliminacionService(
            ArchivoRepository archivos,
            AlmacenamientoS3 almacenamiento,
            VentanaRecepcion ventana,
            BodaProperties propiedades) {
        this.archivos = archivos;
        this.almacenamiento = almacenamiento;
        this.ventana = ventana;
        this.propiedades = propiedades;
    }

    /**
     * @throws ApiException {@code NO_ENCONTRADO} si no existe, no está {@code COMPLETADO} o es de
     *     otro evento
     */
    public void eliminar(String archivoId, String usuario) {
        ArchivoMultimedia archivo = archivos.buscar(archivoId)
                .filter(a -> a.estado() == ArchivoMultimedia.Estado.COMPLETADO)
                .filter(a -> propiedades.eventId().equals(a.eventoId()))
                .orElseThrow(() -> new ApiException(CodigoError.NO_ENCONTRADO));

        almacenamiento.borrar(archivo.claveS3());
        if (archivo.claveMiniatura() != null) {
            almacenamiento.borrar(archivo.claveMiniatura());
        }
        archivos.eliminar(archivo, ventana.ahora(), usuario);
        LOG.info("Archivo {} eliminado por {}", archivoId, usuario);
    }
}
