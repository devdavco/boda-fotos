package co.boda.fotos.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Escribe el cuerpo {@code {codigo, mensaje}} de contracts/api.md desde los filtros de seguridad,
 * que corren antes de Spring MVC y no pasan por {@link ManejadorErrores}.
 */
public final class RespuestaJson {

    private RespuestaJson() {
    }

    public static void escribir(HttpServletResponse res, CodigoError codigo) throws IOException {
        res.setStatus(codigo.estadoHttp());
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter()
                .write("{\"codigo\":\"" + codigo.name() + "\",\"mensaje\":\""
                        + escapar(codigo.mensaje()) + "\"}");
    }

    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
