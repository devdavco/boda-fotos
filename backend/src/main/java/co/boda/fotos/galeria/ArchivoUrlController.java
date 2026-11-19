package co.boda.fotos.galeria;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import co.boda.fotos.config.BodaProperties;
import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;
import co.boda.fotos.recepcion.VentanaRecepcion;
import co.boda.fotos.subida.AlmacenamientoS3;
import co.boda.fotos.subida.ArchivoMultimedia;
import co.boda.fotos.subida.ArchivoRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/admin/archivos/{archivoId}/url?uso=ver|descargar} (FR-027): URL {@code GET}
 * prefirmada de 15 minutos del original. Con {@code uso=descargar}, S3 responde con
 * {@code Content-Disposition: attachment; filename*=UTF-8''<nombre original>} y el navegador lo
 * guarda con el nombre que tenía en el celular. Solo para archivos {@code COMPLETADO} y hasta el fin
 * de disponibilidad (después, {@code 410 PERIODO_TERMINADO}).
 *
 * <p>También {@code DELETE /api/admin/archivos/{archivoId}} (FR-028), que delega en
 * {@link EliminacionService}.
 */
@RestController
public class ArchivoUrlController {

    private static final Pattern UUID_V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ArchivoRepository archivos;
    private final AlmacenamientoS3 almacenamiento;
    private final VentanaRecepcion ventana;
    private final BodaProperties propiedades;
    private final EliminacionService eliminacion;

    public ArchivoUrlController(
            ArchivoRepository archivos,
            AlmacenamientoS3 almacenamiento,
            VentanaRecepcion ventana,
            BodaProperties propiedades,
            EliminacionService eliminacion) {
        this.eliminacion = eliminacion;
        this.archivos = archivos;
        this.almacenamiento = almacenamiento;
        this.ventana = ventana;
        this.propiedades = propiedades;
    }

    @GetMapping("/api/admin/archivos/{archivoId}/url")
    public Map<String, Object> url(@PathVariable String archivoId, @RequestParam(required = false) String uso) {
        boolean descargar;
        if ("ver".equals(uso)) {
            descargar = false;
        } else if ("descargar".equals(uso)) {
            descargar = true;
        } else {
            throw new ApiException(CodigoError.PETICION_INVALIDA);
        }
        validarId(archivoId);
        GaleriaService.exigirPeriodoVigente(ventana);
        ArchivoMultimedia archivo = archivos.buscar(archivoId)
                .filter(a -> a.estado() == ArchivoMultimedia.Estado.COMPLETADO)
                .filter(a -> propiedades.eventId().equals(a.eventoId()))
                .orElseThrow(() -> new ApiException(CodigoError.NO_ENCONTRADO));

        String disposicion = descargar ? adjunto(archivo.nombreOriginal()) : null;
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("url", almacenamiento.urlLectura(archivo.claveS3(), disposicion));
        respuesta.put("expiraEn", ventana.ahora().plus(propiedades.limites().urlLectura()).toString());
        return respuesta;
    }

    /** El usuario autenticado lo deja {@code BearerTokenFilter} como nombre de la autenticación. */
    @DeleteMapping("/api/admin/archivos/{archivoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable String archivoId, Authentication autenticacion) {
        validarId(archivoId);
        eliminacion.eliminar(archivoId, autenticacion.getName());
    }

    private static void validarId(String archivoId) {
        if (archivoId == null || !UUID_V4.matcher(archivoId).matches()) {
            throw new ApiException(CodigoError.NO_ENCONTRADO);
        }
    }

    /** {@code attachment; filename*=UTF-8''<nombre codificado según RFC 5987>}. */
    static String adjunto(String nombreOriginal) {
        return "attachment; filename*=UTF-8''" + codificarRfc5987(nombreOriginal);
    }

    /**
     * Codifica en porcentaje todo lo que no sea {@code attr-char} de RFC 5987 (letras ASCII,
     * dígitos y {@code !#$&+-.^_`|~}). {@code URLEncoder} no sirve: cambia el espacio por {@code +}.
     */
    static String codificarRfc5987(String texto) {
        StringBuilder salida = new StringBuilder();
        for (byte b : texto.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean permitido = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0;
            if (permitido) {
                salida.append((char) c);
            } else {
                salida.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return salida.toString();
    }
}
