package co.boda.fotos.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login y logout de los novios (contracts/api.md, "API de administración"). El login es público;
 * el logout pasa por {@link BearerTokenFilter}, así que solo llega aquí con un token válido.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** Cuerpo de {@code POST /api/admin/login}. */
    public record PeticionLogin(String usuario, String contrasena) {
    }

    private final LoginService login;
    private final SesionAdminRepository sesiones;

    public AdminController(LoginService login, SesionAdminRepository sesiones) {
        this.login = login;
        this.sesiones = sesiones;
    }

    @PostMapping("/login")
    public Map<String, Object> entrar(@RequestBody(required = false) PeticionLogin peticion) {
        LoginService.Sesion sesion = peticion == null
                ? login.entrar(null, null)
                : login.entrar(peticion.usuario(), peticion.contrasena());
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("token", sesion.token());
        respuesta.put("usuario", sesion.usuario());
        respuesta.put("nombreVisible", sesion.nombreVisible());
        respuesta.put("inactividadMaxSegundos", SesionAdminRepository.INACTIVIDAD_MAXIMA.toSeconds());
        return respuesta;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void salir(@RequestHeader(HttpHeaders.AUTHORIZATION) String autorizacion) {
        sesiones.borrar(BearerTokenFilter.token(autorizacion));
    }
}
