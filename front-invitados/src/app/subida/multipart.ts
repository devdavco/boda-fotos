import { ApiError, ParteCompletada, UrlsPartes } from '../api/api';

/**
 * Subida en partes de un video de 100 MB o más (research R2, contracts/api.md; FR-008).
 *
 * - Partes de 10 MiB (las define el servidor al autorizar), hasta 3 en paralelo.
 * - La URL de cada parte se pide **justo antes de enviarla** (una por petición, nunca todas al
 *   inicio): duran 5 minutos y un video de 500 MB por 4G tarda más.
 * - Si el `PUT` de una parte da 403 (URL vencida, por ejemplo tras una pausa de la red), se pide
 *   otra URL y se reintenta sin contarlo como fallo, **una sola vez** seguida por parte: un segundo
 *   403 indica un tamaño distinto del firmado y la subida falla en vez de repetir en bucle.
 * - Sin conexión, cada parte espera y reintenta con espera creciente (hasta 30 s entre intentos)
 *   durante un máximo de 15 minutos: un corte de más de 5 minutos en modo avión no hace fallar la
 *   subida (quickstart §4.2).
 * - Se guarda el `ETag` de cada parte terminada en `EstadoMultipart`: al reintentar el archivo no
 *   se reenvían las partes ya subidas y el progreso no vuelve a 0.
 */

/** Partes que se envían a la vez. */
export const PARTES_EN_PARALELO = 3;
/** Espera máxima entre reintentos de una parte. */
const ESPERA_MAXIMA_MS = 30_000;
/** Tiempo total que una parte sigue reintentando por fallas de red antes de rendirse. */
export const TIEMPO_MAXIMO_SIN_RED_MS = 15 * 60_000;
/** Reintentos ante respuestas de error de S3 que no son 403 (por ejemplo, 500 o 503). */
const MAX_REINTENTOS_ERROR_S3 = 5;

/** Lo que se sabe de una subida en partes; vive en el `ItemSubida` entre reintentos. */
export interface EstadoMultipart {
  archivoId: string;
  numeroPartes: number;
  tamanoParte: number;
  /** `ETag` de cada parte ya subida, por número de parte. */
  etags: Map<number, string>;
}

/** Resultado del `PUT` de una parte: estado HTTP (0 si no hubo respuesta) y su `ETag`. */
export interface RespuestaParte {
  estado: number;
  etag: string | null;
}

/** Lo que el multipart necesita de afuera; se inyecta para poder probarlo sin red. */
export interface DependenciasMultipart {
  /** `POST /api/subidas/{archivoId}/partes` (con renovación de sesión). */
  pedirUrls(numeros: number[]): Promise<UrlsPartes>;
  /** `PUT` de una parte; informa el avance en bytes de esa parte. */
  enviarParte(url: string, cuerpo: Blob, alAvance: (bytes: number) => void): Promise<RespuestaParte>;
  esperar(ms: number): Promise<void>;
}

/** La subida en partes no pudo terminar. `reintentable` indica si "Reintentar" tiene sentido. */
export class ErrorMultipart extends Error {
  constructor(
    readonly causa: 'RED' | 'TAMANO' | 'S3' | 'API',
    readonly errorApi?: ApiError,
  ) {
    super(`Subida en partes fallida: ${causa}`);
    this.name = 'ErrorMultipart';
  }
}

/** Bytes de la parte `numero` (empieza en 1): 10 MiB, y la última lleva el resto. */
export function rangoParte(tamanoTotal: number, tamanoParte: number, numero: number): [number, number] {
  const inicio = (numero - 1) * tamanoParte;
  return [inicio, Math.min(inicio + tamanoParte, tamanoTotal)];
}

/**
 * Sube las partes que faltan y devuelve todas, ordenadas, para `completar`. Llama a `alProgreso`
 * con el porcentaje del archivo completo (contando las partes que ya estaban subidas).
 */
export async function subirPartes(
  archivo: Blob,
  estado: EstadoMultipart,
  deps: DependenciasMultipart,
  alProgreso: (porcentaje: number) => void,
): Promise<ParteCompletada[]> {
  const pendientes: number[] = [];
  for (let n = 1; n <= estado.numeroPartes; n++) {
    if (!estado.etags.has(n)) pendientes.push(n);
  }

  // Avance: bytes de las partes terminadas + bytes enviados de las que están en curso.
  const enCurso = new Map<number, number>();
  const bytesDe = (n: number) => {
    const [inicio, fin] = rangoParte(archivo.size, estado.tamanoParte, n);
    return fin - inicio;
  };
  const informar = () => {
    let enviados = 0;
    for (const n of estado.etags.keys()) enviados += bytesDe(n);
    for (const b of enCurso.values()) enviados += b;
    alProgreso(archivo.size > 0 ? Math.floor((100 * enviados) / archivo.size) : 0);
  };
  informar();

  let fallo: ErrorMultipart | null = null;
  const trabajador = async () => {
    while (fallo === null) {
      const numero = pendientes.shift();
      if (numero === undefined) return;
      try {
        const etag = await subirParte(archivo, estado, numero, deps, (bytes) => {
          enCurso.set(numero, bytes);
          informar();
        });
        estado.etags.set(numero, etag);
      } catch (error) {
        // Una parte que no pudo subir detiene a las demás: las que ya iban terminan y quedan
        // guardadas, y "Reintentar" sigue desde ahí.
        fallo ??= error instanceof ErrorMultipart ? error : new ErrorMultipart('RED');
      } finally {
        enCurso.delete(numero);
        informar();
      }
    }
  };
  await Promise.all(
    Array.from({ length: Math.min(PARTES_EN_PARALELO, pendientes.length) }, () => trabajador()),
  );
  if (fallo !== null) throw fallo;

  return [...estado.etags.entries()]
    .sort(([a], [b]) => a - b)
    .map(([numero, etag]) => ({ numero, etag }));
}

/** Sube una parte con sus reintentos y devuelve su `ETag`. */
async function subirParte(
  archivo: Blob,
  estado: EstadoMultipart,
  numero: number,
  deps: DependenciasMultipart,
  alAvance: (bytes: number) => void,
): Promise<string> {
  const [inicio, fin] = rangoParte(archivo.size, estado.tamanoParte, numero);
  const cuerpo = archivo.slice(inicio, fin);
  let sinRedDesde: number | null = null;
  let espera = 1000;
  let erroresS3 = 0;
  let ultimo403 = false;

  const esperarYSeguir = async (causa: 'RED' | 'S3') => {
    if (causa === 'RED') {
      sinRedDesde ??= Date.now();
      if (Date.now() - sinRedDesde > TIEMPO_MAXIMO_SIN_RED_MS) throw new ErrorMultipart('RED');
    } else if (++erroresS3 > MAX_REINTENTOS_ERROR_S3) {
      throw new ErrorMultipart('S3');
    }
    await deps.esperar(espera);
    espera = Math.min(espera * 2, ESPERA_MAXIMA_MS);
  };

  for (;;) {
    // 1. URL de esta parte, recién firmada.
    let url: string;
    try {
      const respuesta = await deps.pedirUrls([numero]);
      const encontrada = respuesta.urls.find((u) => u.numero === numero);
      if (!encontrada) throw new ErrorMultipart('S3');
      url = encontrada.url;
    } catch (error) {
      if (error instanceof ApiError && (error.estado === 0 || error.estado >= 500)) {
        await esperarYSeguir('RED'); // sin conexión con el API: igual que sin red
        continue;
      }
      if (error instanceof ApiError) throw new ErrorMultipart('API', error);
      throw error;
    }

    // 2. PUT de la parte.
    alAvance(0);
    const { estado: http, etag } = await deps.enviarParte(url, cuerpo, alAvance);
    if (http !== 0) {
      // S3 respondió: hay conexión otra vez y el próximo corte cuenta desde cero. (Que el API
      // responda no basta: el API y S3 son destinos distintos.)
      sinRedDesde = null;
      espera = 1000;
    }
    if (http >= 200 && http < 300) {
      if (!etag) throw new ErrorMultipart('S3'); // sin ETag no se puede completar (CORS mal configurado)
      return etag;
    }
    alAvance(0);
    if (http === 403) {
      if (ultimo403) throw new ErrorMultipart('TAMANO');
      ultimo403 = true; // URL vencida: se pide otra sin contarlo como fallo
      continue;
    }
    ultimo403 = false;
    if (http === 0) {
      await esperarYSeguir('RED');
    } else {
      await esperarYSeguir('S3');
    }
  }
}

/**
 * Si un `PUT` pasa este tiempo sin avanzar, se aborta y cuenta como falla de red. Al pasar de WiFi
 * a 4G la conexión puede quedarse colgada sin ningún evento de error, y sin esto la parte
 * esperaría para siempre. No es un tiempo máximo total: una parte lenta pero que avanza sigue.
 */
export const SIN_AVANCE_MS = 60_000;

/**
 * `PUT` de una parte con `XMLHttpRequest` (informa el avance, research R13) y lectura del `ETag`,
 * que el CORS del bucket expone (`ExposeHeaders: ETag`). Sin `Content-Type`: la URL de la parte
 * solo firma el tamaño.
 */
export function enviarParteXhr(
  url: string,
  cuerpo: Blob,
  alAvance: (bytes: number) => void,
): Promise<RespuestaParte> {
  return new Promise((resolver) => {
    const xhr = new XMLHttpRequest();
    let vigilante: ReturnType<typeof setTimeout> | undefined;
    const vigilar = () => {
      clearTimeout(vigilante);
      vigilante = setTimeout(() => xhr.abort(), SIN_AVANCE_MS);
    };
    const terminar = (respuesta: RespuestaParte) => {
      clearTimeout(vigilante);
      resolver(respuesta);
    };
    xhr.open('PUT', url);
    xhr.upload.onprogress = (e) => {
      vigilar();
      alAvance(e.loaded);
    };
    xhr.onload = () => terminar({ estado: xhr.status, etag: xhr.getResponseHeader('ETag') });
    xhr.onerror = () => terminar({ estado: 0, etag: null });
    xhr.ontimeout = () => terminar({ estado: 0, etag: null });
    xhr.onabort = () => terminar({ estado: 0, etag: null });
    vigilar();
    xhr.send(cuerpo);
  });
}
