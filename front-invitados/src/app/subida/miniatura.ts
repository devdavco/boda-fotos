import { Formato } from './tipos';

/**
 * Miniatura JPEG generada en el celular (research R4; FR-026, FR-026a): lado mayor de 480 px y
 * calidad 0,7 (unos 30–80 KB).
 *
 * - Fotos JPG, PNG y WEBP: `createImageBitmap` y `<canvas>`.
 * - Videos: `<video muted playsinline>` con un object URL, el fotograma del segundo 1 (o del 0 si
 *   dura menos).
 * - HEIC no se intenta: la galería muestra el ícono (los iPhone ya entregan JPEG, research R13).
 *
 * Nunca bloquea la subida: si algo falla o tarda más de 5 s, devuelve `null` y el archivo sube sin
 * miniatura. `cola-subida.ts` aplica además su propio tiempo máximo y el límite de 1 MB.
 */

export const LADO_MAYOR_PX = 480;
export const CALIDAD_JPEG = 0.7;
const TIEMPO_MAXIMO_MS = 5000;

export async function generarMiniatura(archivo: File, formato: Formato): Promise<Blob | null> {
  if (formato.clave === 'heic') return null;
  try {
    return await conTiempoMaximo(
      formato.tipo === 'VIDEO' ? (cancelado) => desdeVideo(archivo, cancelado) : () => desdeFoto(archivo),
    );
  } catch {
    return null;
  }
}

/** Ancho y alto escalados para que el lado mayor sea, como mucho, `LADO_MAYOR_PX`. */
export function medidasMiniatura(ancho: number, alto: number): [number, number] {
  const escala = Math.min(1, LADO_MAYOR_PX / Math.max(ancho, alto));
  return [Math.max(1, Math.round(ancho * escala)), Math.max(1, Math.round(alto * escala))];
}

/** Ejecuta la tarea con un tiempo máximo; al vencer avisa a la tarea para que libere recursos. */
function conTiempoMaximo<T>(tarea: (cancelado: () => boolean) => Promise<T>): Promise<T | null> {
  let vencido = false;
  return Promise.race([
    tarea(() => vencido),
    new Promise<null>((resolver) =>
      setTimeout(() => {
        vencido = true;
        resolver(null);
      }, TIEMPO_MAXIMO_MS),
    ),
  ]);
}

async function desdeFoto(archivo: File): Promise<Blob | null> {
  if (typeof createImageBitmap === 'function') {
    // `from-image` respeta la orientación EXIF (fotos verticales de celular).
    const imagen = await createImageBitmap(archivo, { imageOrientation: 'from-image' });
    try {
      return await dibujar(imagen, imagen.width, imagen.height);
    } finally {
      imagen.close();
    }
  }
  // Navegadores sin createImageBitmap: un <img> con object URL.
  const url = URL.createObjectURL(archivo);
  try {
    const img = new Image();
    img.decoding = 'async';
    img.src = url;
    await img.decode();
    return await dibujar(img, img.naturalWidth, img.naturalHeight);
  } finally {
    URL.revokeObjectURL(url);
  }
}

function desdeVideo(archivo: File, cancelado: () => boolean): Promise<Blob | null> {
  return new Promise((resolver, rechazar) => {
    const url = URL.createObjectURL(archivo);
    const video = document.createElement('video');
    const limpiar = () => {
      video.removeAttribute('src');
      video.load(); // suelta el decodificador
      URL.revokeObjectURL(url);
    };
    const fallar = () => {
      limpiar();
      rechazar(new Error('No se pudo leer el video'));
    };
    const capturar = () => {
      if (cancelado()) return fallar();
      dibujar(video, video.videoWidth, video.videoHeight)
        .then((blob) => {
          limpiar();
          resolver(blob);
        })
        .catch(fallar);
    };

    video.muted = true;
    video.playsInline = true;
    video.setAttribute('playsinline', '');
    video.preload = 'auto';
    video.onerror = fallar;
    video.onloadedmetadata = () => {
      if (cancelado() || !video.videoWidth || !video.videoHeight) return fallar();
      const segundo = Number.isFinite(video.duration) && video.duration >= 1 ? 1 : 0;
      if (segundo === 0) {
        // Ir a 0 cuando ya se está en 0 no dispara "seeked": se espera el primer fotograma.
        if (video.readyState >= 2) capturar();
        else video.onloadeddata = capturar;
      } else {
        video.onseeked = capturar;
        video.currentTime = segundo;
      }
    };
    video.src = url;
  });
}

/** Dibuja la fuente escalada en un `<canvas>` y la exporta como JPEG. */
function dibujar(fuente: CanvasImageSource, ancho: number, alto: number): Promise<Blob | null> {
  if (!ancho || !alto) return Promise.resolve(null);
  const [w, h] = medidasMiniatura(ancho, alto);
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  if (!ctx) return Promise.resolve(null);
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(fuente, 0, 0, w, h);
  return new Promise((resolver) => canvas.toBlob((blob) => resolver(blob), 'image/jpeg', CALIDAD_JPEG));
}
