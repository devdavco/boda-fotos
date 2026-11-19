package co.boda.fotos.galeria;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Resumen y galería de los novios (contracts/api.md). Exige sesión de administrador. */
@RestController
@RequestMapping("/api/admin")
public class GaleriaController {

    private final GaleriaService servicio;

    public GaleriaController(GaleriaService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/resumen")
    public Map<String, Object> resumen() {
        return servicio.resumen();
    }

    /** {@code limite} llega como texto para responder {@code PETICION_INVALIDA} si no es un número. */
    @GetMapping("/galeria")
    public Map<String, Object> galeria(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limite) {
        return servicio.galeria(cursor, limite);
    }
}
