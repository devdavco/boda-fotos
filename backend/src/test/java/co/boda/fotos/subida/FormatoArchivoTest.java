package co.boda.fotos.subida;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.boda.fotos.config.BodaProperties.Limites;
import co.boda.fotos.error.ApiException;
import co.boda.fotos.error.CodigoError;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Formatos permitidos, límites de tamaño y firmas de bytes (FR-010, FR-011, research R2). T058. */
class FormatoArchivoTest {

    /** Los valores por defecto de {@code BodaProperties.Limites}. */
    private static final Limites LIMITES = new Limites(5, 15, 41_943_040L, 524_288_000L, 104_857_600L, 10_485_760L, 1_048_576L);

    @ParameterizedTest
    @CsvSource({
        "IMG_0001.JPG, JPG",
        "foto.jpeg, JPG",
        "captura.png, PNG",
        "iphone.HEIC, HEIC",
        "web.webp, WEBP",
        "video.mp4, MP4",
        "IMG_2041.MOV, MOV",
        "'nombre con espacios y ñ.jpg', JPG",
        "doble.punto.mp4, MP4",
    })
    void reconoceLasExtensionesPermitidasSinImportarMayusculas(String nombre, FormatoArchivo esperado) {
        assertThat(FormatoArchivo.porNombre(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @ValueSource(strings = {"documento.pdf", "animacion.gif", "video.avi", "sin-extension", "termina-en-punto.", "jpg"})
    void rechazaExtensionesNoPermitidas(String nombre) {
        assertThatThrownBy(() -> FormatoArchivo.porNombre(nombre))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).codigo())
                .isEqualTo(CodigoError.TIPO_NO_PERMITIDO);
    }

    @Test
    void rechazaNombreNulo() {
        assertThatThrownBy(() -> FormatoArchivo.porNombre(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void fotoHasta40MegasYVideoHasta500() {
        assertThatCode(() -> FormatoArchivo.JPG.validarTamano(41_943_040L, LIMITES)).doesNotThrowAnyException();
        assertThatCode(() -> FormatoArchivo.MP4.validarTamano(524_288_000L, LIMITES)).doesNotThrowAnyException();

        assertThatThrownBy(() -> FormatoArchivo.JPG.validarTamano(41_943_041L, LIMITES))
                .isInstanceOf(ApiException.class)
                .hasMessage("La foto supera el máximo de 40 MB.");
        assertThatThrownBy(() -> FormatoArchivo.MOV.validarTamano(524_288_001L, LIMITES))
                .isInstanceOf(ApiException.class)
                .hasMessage("El video supera el máximo de 500 MB. Elige uno más corto.");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rechazaArchivosVacios(long bytes) {
        assertThatThrownBy(() -> FormatoArchivo.PNG.validarTamano(bytes, LIMITES))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).codigo())
                .isEqualTo(CodigoError.ARCHIVO_VACIO);
    }

    @Test
    void soloLosVideosDe100MegasOMasUsanMultipart() {
        assertThat(FormatoArchivo.MP4.usaMultipart(104_857_599L, LIMITES)).isFalse();
        assertThat(FormatoArchivo.MP4.usaMultipart(104_857_600L, LIMITES)).isTrue();
        assertThat(FormatoArchivo.JPG.usaMultipart(41_943_040L, LIMITES)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"image/jpeg", "IMAGE/JPG", "image/jpeg; charset=binary"})
    void aceptaTipoDeContenidoAusenteOCoherente(String tipo) {
        assertThatCode(() -> FormatoArchivo.JPG.validarTipoContenido(tipo)).doesNotThrowAnyException();
    }

    @Test
    void rechazaTipoDeContenidoQueContradiceLaExtension() {
        assertThatThrownBy(() -> FormatoArchivo.JPG.validarTipoContenido("video/mp4"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).codigo())
                .isEqualTo(CodigoError.TIPO_NO_PERMITIDO);
        assertThatCode(() -> FormatoArchivo.HEIC.validarTipoContenido("image/heif")).doesNotThrowAnyException();
    }

    @Test
    void firmasValidas() {
        assertThat(FormatoArchivo.JPG.firmaCoincide(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isTrue();
        assertThat(FormatoArchivo.PNG.firmaCoincide(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A))).isTrue();
        assertThat(FormatoArchivo.WEBP.firmaCoincide(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'))).isTrue();
        byte[] ftyp = bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm');
        assertThat(FormatoArchivo.MP4.firmaCoincide(ftyp)).isTrue();
        assertThat(FormatoArchivo.MOV.firmaCoincide(ftyp)).isTrue();
        assertThat(FormatoArchivo.HEIC.firmaCoincide(ftyp)).isTrue();
    }

    @Test
    void firmasInvalidas() {
        byte[] png = bytes(0x89, 0x50, 0x4E, 0x47);
        assertThat(FormatoArchivo.JPG.firmaCoincide(png)).isFalse();
        assertThat(FormatoArchivo.PNG.firmaCoincide(bytes(0xFF, 0xD8, 0xFF))).isFalse();
        assertThat(FormatoArchivo.WEBP.firmaCoincide(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'))).isFalse();
        assertThat(FormatoArchivo.MP4.firmaCoincide(png)).isFalse();
        assertThat(FormatoArchivo.JPG.firmaCoincide(new byte[0])).isFalse();
        assertThat(FormatoArchivo.JPG.firmaCoincide(bytes(0xFF))).isFalse();
    }

    private static byte[] bytes(int... valores) {
        byte[] b = new byte[valores.length];
        for (int i = 0; i < valores.length; i++) {
            b[i] = (byte) valores[i];
        }
        return b;
    }
}
