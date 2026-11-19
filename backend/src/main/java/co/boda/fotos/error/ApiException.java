package co.boda.fotos.error;

/**
 * Error de negocio con código del catálogo ({@link CodigoError}). {@link ManejadorErrores} lo
 * convierte en {@code {"codigo": ..., "mensaje": ...}} con el estado HTTP del código.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CodigoError codigo;

    public ApiException(CodigoError codigo) {
        super(codigo.mensaje());
        this.codigo = codigo;
    }

    /** Para los casos en que el contrato define más de un mensaje por código. */
    public ApiException(CodigoError codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    /** {@code ARCHIVO_MUY_GRANDE} para una foto de más de 40 MB. */
    public static ApiException fotoMuyGrande() {
        return new ApiException(CodigoError.ARCHIVO_MUY_GRANDE, "La foto supera el máximo de 40 MB.");
    }

    /** {@code ARCHIVO_MUY_GRANDE} para un video de más de 500 MB. */
    public static ApiException videoMuyGrande() {
        return new ApiException(
                CodigoError.ARCHIVO_MUY_GRANDE, "El video supera el máximo de 500 MB. Elige uno más corto.");
    }

    public CodigoError codigo() {
        return codigo;
    }
}
