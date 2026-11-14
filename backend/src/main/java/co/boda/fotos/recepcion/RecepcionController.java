package co.boda.fotos.recepcion;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Esqueleto: respuesta fija con la forma de contracts/api.md. Se reemplaza por el cálculo
 * real de la ventana de recepción en T059.
 */
@RestController
public class RecepcionController {

    @GetMapping("/api/recepcion/estado")
    public Map<String, Object> estado() {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("estado", "NO_INICIADA");
        respuesta.put("aperturaEn", "2026-11-19T05:00:00Z");
        respuesta.put("cierreEn", "2026-11-22T17:00:00Z");
        respuesta.put("ahora", Instant.now().toString());
        return respuesta;
    }
}
