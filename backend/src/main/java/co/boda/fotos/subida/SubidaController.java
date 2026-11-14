package co.boda.fotos.subida;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;
import co.boda.fotos.subida.SubidaService.PeticionCompletar;
import co.boda.fotos.subida.SubidaService.PeticionSubida;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de subida de los invitados (contracts/api.md). No hay cuentas de invitado: cada petición
 * lleva el encabezado {@code X-Sesion-Subida} con la sesión que emitió el servidor, y solo puede
 * operar sobre archivos de esa sesión (si no, {@code NO_ENCONTRADO}).
 */
@RestController
@RequestMapping("/api/subidas")
public class SubidaController {

    static final String ENCABEZADO_SESION = "X-Sesion-Subida";

    private static final Pattern UUID_V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** Cuerpo de {@code POST /api/subidas/{archivoId}/partes}. */
    public record PeticionPartes(List<Integer> partes) {
    }

    private final SubidaService servicio;

    public SubidaController(SubidaService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> autorizar(
            @RequestHeader(name = ENCABEZADO_SESION, required = false) String sesion,
            @RequestBody PeticionSubida peticion) {
        return servicio.autorizar(sesion, peticion);
    }

    @PostMapping("/{archivoId}/url")
    public Map<String, Object> nuevaUrl(
            @RequestHeader(name = ENCABEZADO_SESION, required = false) String sesion,
            @PathVariable String archivoId) {
        return servicio.nuevaUrl(sesion, id(archivoId));
    }

    @PostMapping("/{archivoId}/partes")
    public Map<String, Object> partes(
            @RequestHeader(name = ENCABEZADO_SESION, required = false) String sesion,
            @PathVariable String archivoId,
            @RequestBody PeticionPartes peticion) {
        return servicio.partes(sesion, id(archivoId), peticion.partes());
    }

    @PostMapping("/{archivoId}/completar")
    public Map<String, Object> completar(
            @RequestHeader(name = ENCABEZADO_SESION, required = false) String sesion,
            @PathVariable String archivoId,
            @RequestBody(required = false) PeticionCompletar peticion) {
        return servicio.completar(sesion, id(archivoId), peticion);
    }

    @DeleteMapping("/{archivoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelar(
            @RequestHeader(name = ENCABEZADO_SESION, required = false) String sesion,
            @PathVariable String archivoId) {
        servicio.cancelar(sesion, id(archivoId));
    }

    /** Un identificador que no es un UUID no puede existir: se presenta como {@code NO_ENCONTRADO}. */
    private static String id(String archivoId) {
        if (archivoId == null || !UUID_V4.matcher(archivoId).matches()) {
            throw new ApiException(CodigoError.NO_ENCONTRADO);
        }
        return archivoId;
    }
}
