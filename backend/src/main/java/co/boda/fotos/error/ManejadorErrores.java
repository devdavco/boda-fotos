package co.boda.fotos.error;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Toda respuesta de error del API tiene el cuerpo {@code {"codigo": ..., "mensaje": ...}} de
 * contracts/api.md. Los errores propios ({@link ApiException}) llevan el estado de su código; los
 * de Spring MVC conservan su estado (405, 415, ...) con el cuerpo uniforme; cualquier otra cosa es
 * un {@code ERROR_INTERNO} 500 sin detalles técnicos (el detalle va al log).
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> apiException(ApiException e) {
        return responder(e.codigo().estadoHttp(), e.codigo(), e.getMessage());
    }

    /** JSON mal formado o validación fallida. */
    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class})
    ResponseEntity<Map<String, String>> peticionInvalida(Exception e) {
        LOG.debug("Petición inválida: {}", e.getMessage());
        return responder(400, CodigoError.PETICION_INVALIDA, CodigoError.PETICION_INVALIDA.mensaje());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> otras(Exception e) {
        if (e instanceof ErrorResponse errorSpring) {
            int estado = errorSpring.getStatusCode().value();
            if (estado >= 400 && estado < 500) {
                CodigoError codigo =
                        estado == 404 ? CodigoError.NO_ENCONTRADO : CodigoError.PETICION_INVALIDA;
                return ResponseEntity.status(estado)
                        .headers(errorSpring.getHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(cuerpo(codigo, codigo.mensaje()));
            }
        }
        LOG.error("Error no controlado", e);
        return responder(500, CodigoError.ERROR_INTERNO, CodigoError.ERROR_INTERNO.mensaje());
    }

    private static ResponseEntity<Map<String, String>> responder(
            int estado, CodigoError codigo, String mensaje) {
        return ResponseEntity.status(estado)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo(codigo, mensaje));
    }

    private static Map<String, String> cuerpo(CodigoError codigo, String mensaje) {
        Map<String, String> cuerpo = new LinkedHashMap<>();
        cuerpo.put("codigo", codigo.name());
        cuerpo.put("mensaje", mensaje);
        return cuerpo;
    }
}
