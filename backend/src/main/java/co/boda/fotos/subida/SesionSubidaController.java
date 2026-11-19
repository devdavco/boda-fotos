package co.boda.fotos.subida;

import co.boda.fotos.subida.SesionSubidaService.SesionCreada;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/sesiones-subida} (contracts/api.md). Público: el invitado no tiene cuenta. */
@RestController
public class SesionSubidaController {

    /** Cuerpo de la petición. */
    public record PeticionSesion(String nombre) {
    }

    private final SesionSubidaService servicio;

    public SesionSubidaController(SesionSubidaService servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/api/sesiones-subida")
    @ResponseStatus(HttpStatus.CREATED)
    public SesionCreada crear(@RequestBody PeticionSesion peticion) {
        return servicio.crear(peticion.nombre());
    }
}
