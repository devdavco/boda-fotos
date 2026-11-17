package co.boda.fotos.recepcion;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estado de la ventana de recepción (contracts/api.md). Delega en {@link VentanaRecepcion}, el
 * mismo servicio y la misma lectura sin caché de {@code boda-evento} que usan los endpoints de
 * subida, así que un cambio en la consola (apertura, cierre o cierre anticipado) se ve de
 * inmediato aquí y en ellos.
 */
@RestController
public class RecepcionController {

    private final VentanaRecepcion ventana;

    public RecepcionController(VentanaRecepcion ventana) {
        this.ventana = ventana;
    }

    @GetMapping("/api/recepcion/estado")
    public Map<String, Object> estado() {
        VentanaRecepcion.Consulta consulta = ventana.consultar();
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("estado", consulta.estado().name());
        respuesta.put("aperturaEn", consulta.aperturaEn().toString());
        respuesta.put("cierreEn", consulta.cierreEn().toString());
        respuesta.put("ahora", consulta.ahora().toString());
        return respuesta;
    }
}
