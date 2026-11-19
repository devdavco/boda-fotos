package co.boda.fotos.subida;

import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;

/**
 * Nombre que declara el invitado (FR-004): de 1 a 60 caracteres tras recortar los espacios y sin
 * caracteres de control. Es un dato declarado, no una identidad verificada.
 */
final class NombreInvitado {

    static final int MAXIMO = 60;

    private NombreInvitado() {
    }

    /**
     * @return el nombre sin espacios al inicio ni al final
     * @throws ApiException {@code NOMBRE_INVALIDO} si es nulo, vacío, solo espacios, de más de 60
     *     caracteres o contiene caracteres de control
     */
    static String validar(String nombre) {
        if (nombre == null) {
            throw new ApiException(CodigoError.NOMBRE_INVALIDO);
        }
        String recortado = nombre.strip();
        int caracteres = recortado.codePointCount(0, recortado.length());
        if (caracteres < 1 || caracteres > MAXIMO || recortado.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(CodigoError.NOMBRE_INVALIDO);
        }
        return recortado;
    }
}
