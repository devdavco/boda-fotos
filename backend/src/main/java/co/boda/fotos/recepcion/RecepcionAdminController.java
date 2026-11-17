package co.boda.fotos.recepcion;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/admin/recepcion/cerrar} (contracts/api.md). Exige sesión de administrador. */
@RestController
public class RecepcionAdminController {

    private final CierreAnticipadoService cierre;

    public RecepcionAdminController(CierreAnticipadoService cierre) {
        this.cierre = cierre;
    }

    /** El usuario autenticado lo deja {@code BearerTokenFilter} como nombre de la autenticación. */
    @PostMapping("/api/admin/recepcion/cerrar")
    public Map<String, Object> cerrar(Authentication autenticacion) {
        return cierre.cerrar(autenticacion.getName());
    }
}
