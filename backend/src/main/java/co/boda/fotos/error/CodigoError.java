package co.boda.fotos.error;

/**
 * Catálogo de errores de contracts/api.md (sección "Errores"): estado HTTP, código y mensaje en
 * español para el usuario. El front muestra el mensaje tal cual (Principio VI), así que el texto
 * es el del contrato, palabra por palabra.
 */
public enum CodigoError {

    /** nombre vacío o de más de 60 caracteres. */
    NOMBRE_INVALIDO(400, "Escribe tu nombre (máximo 60 caracteres)."),
    /** extensión o tipo fuera de la lista. */
    TIPO_NO_PERMITIDO(400, "Este tipo de archivo no se puede subir. Usa fotos JPG, PNG, HEIC o WEBP, o videos MP4 o MOV."),
    /** tamaño 0. */
    ARCHIVO_VACIO(400, "El archivo está vacío. Elige otro."),
    /** > 40 MB foto o > 500 MB video. */
    ARCHIVO_MUY_GRANDE(400, "La foto supera el máximo de 40 MB."),
    /** JSON mal formado o campos faltantes. */
    PETICION_INVALIDA(400, "No pudimos procesar la solicitud. Inténtalo de nuevo."),
    /** `X-Sesion-Subida` ausente, desconocido o vencido. */
    SESION_INVALIDA(401, "Tu sesión se reinició. Vuelve a escribir tu nombre."),
    /** token de admin ausente, inválido o vencido. */
    NO_AUTENTICADO(401, "Tu sesión terminó. Inicia sesión de nuevo."),
    /** login fallido o cuenta bloqueada. */
    CREDENCIALES_INVALIDAS(401, "Usuario o contraseña incorrectos. Si fallaste varias veces, espera 15 minutos e inténtalo de nuevo."),
    /** antes de la apertura. */
    RECEPCION_NO_INICIADA(403, "La recepción de fotos abre el jueves 19 de noviembre."),
    /** después del cierre o del cierre anticipado. */
    RECEPCION_CERRADA(403, "La recepción de fotos y videos ya cerró. ¡Gracias por compartir!"),
    /** archivo inexistente, eliminado o de otra sesión. */
    NO_ENCONTRADO(404, "Este archivo ya no está disponible."),
    /** se alcanzó el tope de 300 archivos o 5 GB. */
    TOPE_SESION(409, "Llegaste al límite de 300 archivos o 5 GB desde este celular. ¡Gracias por compartir tanto!"),
    /** al completar, el objeto o alguna parte no está en S3. */
    SUBIDA_INCOMPLETA(409, "La subida no terminó. Toca \"Reintentar\"."),
    /** tamaño, tipo o firma del archivo no coinciden con lo autorizado. */
    CONTENIDO_INCONSISTENTE(422, "El archivo no parece ser una foto o video válido. Elige otro."),
    /** galería después del 2026-12-01 23:59 (Cali). */
    PERIODO_TERMINADO(410, "El periodo para ver y descargar terminó el 1 de diciembre. Para recuperar el material, escribe al responsable técnico."),
    /** cualquier otro. */
    ERROR_INTERNO(500, "Algo falló de nuestro lado. Inténtalo de nuevo en unos segundos.");

    private final int estadoHttp;
    private final String mensaje;

    CodigoError(int estadoHttp, String mensaje) {
        this.estadoHttp = estadoHttp;
        this.mensaje = mensaje;
    }

    public int estadoHttp() {
        return estadoHttp;
    }

    /** Mensaje por defecto. {@code ARCHIVO_MUY_GRANDE} tiene dos variantes: ver {@link ApiException}. */
    public String mensaje() {
        return mensaje;
    }
}
