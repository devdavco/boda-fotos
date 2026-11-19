import {
  HttpClient,
  HttpErrorResponse,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { catchError, firstValueFrom, throwError } from 'rxjs';

/**
 * Cliente tipado del API de invitados (contracts/api.md, "API de invitados").
 *
 * Todas las llamadas usan rutas relativas (`/api/...`): en producción las reenvía el rewrite de
 * Amplify y en desarrollo `proxy.conf.json`. Solo los `PUT` a S3 quedan fuera de este cliente: los
 * hace `cola-subida.ts` con `XMLHttpRequest` para informar el progreso (research R13).
 */

/** Claves de `localStorage` (FR-004a, contracts/ui.md). */
export const CLAVE_SESION_ID = 'boda.sesionId';
export const CLAVE_NOMBRE = 'boda.nombre';

/** Encabezado con la sesión que emitió el servidor. */
export const ENCABEZADO_SESION = 'X-Sesion-Subida';

/** Máximo de partes que acepta el API en una sola petición a `/partes`. */
export const MAX_PARTES_POR_PETICION = 3;

/**
 * `localStorage` puede lanzar (almacenamiento bloqueado o lleno): la sesión recordada es una
 * comodidad, así que un fallo se trata como "no hay nada guardado".
 */
export function leerGuardado(clave: string): string | null {
  try {
    return localStorage.getItem(clave);
  } catch {
    return null;
  }
}

export function guardar(clave: string, valor: string): void {
  try {
    localStorage.setItem(clave, valor);
  } catch {
    // Sin almacenamiento: la sesión vale solo mientras la página siga abierta.
  }
}

export function borrarGuardado(clave: string): void {
  try {
    localStorage.removeItem(clave);
  } catch {
    // Nada que borrar.
  }
}

let sesionEnMemoria: string | null = null;

/** Sesión de subida vigente: la de esta página si se creó aquí, si no la guardada. */
export function leerSesionId(): string | null {
  return sesionEnMemoria ?? leerGuardado(CLAVE_SESION_ID);
}

/** Se mantiene también en memoria para que la subida funcione si `localStorage` no deja escribir. */
export function guardarSesionId(sesionId: string): void {
  sesionEnMemoria = sesionId;
  guardar(CLAVE_SESION_ID, sesionId);
}

export function borrarSesionId(): void {
  sesionEnMemoria = null;
  borrarGuardado(CLAVE_SESION_ID);
}

/**
 * Error del API: `mensaje` está en español y se muestra tal cual al usuario (Principio VI).
 * `codigo` es el del contrato (`SESION_INVALIDA`, `TOPE_SESION`...) o uno local (`SIN_CONEXION`).
 */
export class ApiError extends Error {
  constructor(
    readonly codigo: string,
    readonly mensaje: string,
    readonly estado: number,
  ) {
    super(mensaje);
    this.name = 'ApiError';
  }
}

export const MENSAJE_SIN_CONEXION =
  'No hay conexión con el servidor. Revisa tu internet e inténtalo de nuevo.';
export const MENSAJE_ERROR_INTERNO =
  'Algo falló de nuestro lado. Inténtalo de nuevo en unos segundos.';

/** Convierte cualquier error de `HttpClient` en un `ApiError` con mensaje en español. */
export function aApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const cuerpo: unknown = error.error;
    if (cuerpo !== null && typeof cuerpo === 'object') {
      const { codigo, mensaje } = cuerpo as { codigo?: unknown; mensaje?: unknown };
      if (typeof codigo === 'string' && typeof mensaje === 'string' && mensaje !== '') {
        return new ApiError(codigo, mensaje, error.status);
      }
    }
    // Sin respuesta (red caída, bloqueo) o con una que no es del API (por ejemplo, un 502 del
    // proxy): no hay `mensaje` del servidor que mostrar.
    if (error.status === 0) return new ApiError('SIN_CONEXION', MENSAJE_SIN_CONEXION, 0);
    return new ApiError('ERROR_INTERNO', MENSAJE_ERROR_INTERNO, error.status);
  }
  return new ApiError('ERROR_INTERNO', MENSAJE_ERROR_INTERNO, 0);
}

function esLlamadaDeSubida(req: HttpRequest<unknown>): boolean {
  return req.url === '/api/subidas' || req.url.startsWith('/api/subidas/');
}

/**
 * Agrega `X-Sesion-Subida` a las llamadas de subida y convierte los errores `{codigo, mensaje}` en
 * `ApiError`. La sesión se lee en cada petición, así que una sesión renovada tras `SESION_INVALIDA`
 * se usa de inmediato.
 */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) return next(req);
  const sesionId = esLlamadaDeSubida(req) ? leerSesionId() : null;
  const peticion = sesionId ? req.clone({ setHeaders: { [ENCABEZADO_SESION]: sesionId } }) : req;
  return next(peticion).pipe(catchError((error) => throwError(() => aApiError(error))));
};

export interface EstadoRecepcion {
  estado: 'NO_INICIADA' | 'ABIERTA' | 'CERRADA';
  aperturaEn: string;
  cierreEn: string;
  ahora: string;
}

export interface SesionCreada {
  sesionId: string;
  nombre: string;
  maxArchivos: number;
  maxBytes: number;
}

export interface PeticionAutorizar {
  nombreInvitado: string;
  nombreArchivo: string;
  /** Opcional: si se envía, no debe contradecir la extensión. */
  tipoContenido?: string;
  tamano: number;
  /** `true` si el navegador ya generó la miniatura y la subirá. */
  conMiniatura: boolean;
  /**
   * Bytes de la miniatura ya generada (1 a 1.048.576). La URL de la miniatura firma ese tamaño,
   * así que la miniatura se genera antes de autorizar (research R2 y R4).
   */
  tamanoMiniatura?: number;
}

/** Autorización de un archivo en modo simple (< 100 MB, o cualquier foto). */
export interface AutorizacionSimple {
  archivoId: string;
  modo: 'SIMPLE';
  url: string;
  /** Encabezados exactos del `PUT`; hoy solo `Content-Type`. */
  encabezados: Record<string, string>;
  /** Solo viene al autorizar con miniatura válida; `POST .../url` no la renueva. */
  urlMiniatura?: string;
  expiraEn: string;
}

/** Autorización de un video de 100 MB o más (multipart). */
export interface AutorizacionMultipart {
  archivoId: string;
  modo: 'MULTIPART';
  tamanoParte: number;
  numeroPartes: number;
  urlMiniatura?: string;
  expiraEn: string;
}

export type Autorizacion = AutorizacionSimple | AutorizacionMultipart;

export interface UrlsPartes {
  urls: { numero: number; url: string }[];
  expiraEn: string;
}

export interface ParteCompletada {
  numero: number;
  etag: string;
}

export interface PeticionCompletar {
  /** Solo en multipart, todas y en orden. */
  partes?: ParteCompletada[];
  miniaturaSubida: boolean;
}

export interface ResultadoCompletar {
  archivoId: string;
  estado: 'COMPLETADO';
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  estado(): Promise<EstadoRecepcion> {
    return firstValueFrom(this.http.get<EstadoRecepcion>('/api/recepcion/estado'));
  }

  crearSesion(nombre: string): Promise<SesionCreada> {
    return firstValueFrom(this.http.post<SesionCreada>('/api/sesiones-subida', { nombre }));
  }

  /** `POST /api/subidas`: se llama cuando el archivo empieza a subir, no al elegirlo. */
  autorizar(peticion: PeticionAutorizar): Promise<Autorizacion> {
    return firstValueFrom(this.http.post<Autorizacion>('/api/subidas', peticion));
  }

  /** Nueva URL para reintentar un archivo simple ya autorizado. No suma al tope. */
  nuevaUrl(archivoId: string): Promise<AutorizacionSimple> {
    return firstValueFrom(
      this.http.post<AutorizacionSimple>(`/api/subidas/${encodeURIComponent(archivoId)}/url`, {}),
    );
  }

  /**
   * URLs de partes de un multipart. Se piden por parte y justo antes de enviarla (como máximo
   * `MAX_PARTES_POR_PETICION` números, los que salen en paralelo), nunca todas al inicio.
   */
  partes(archivoId: string, numeros: number[]): Promise<UrlsPartes> {
    if (numeros.length < 1 || numeros.length > MAX_PARTES_POR_PETICION) {
      return Promise.reject(
        new ApiError(
          'PETICION_INVALIDA',
          'No pudimos procesar la solicitud. Inténtalo de nuevo.',
          400,
        ),
      );
    }
    return firstValueFrom(
      this.http.post<UrlsPartes>(`/api/subidas/${encodeURIComponent(archivoId)}/partes`, {
        partes: numeros,
      }),
    );
  }

  completar(archivoId: string, cuerpo: PeticionCompletar): Promise<ResultadoCompletar> {
    return firstValueFrom(
      this.http.post<ResultadoCompletar>(
        `/api/subidas/${encodeURIComponent(archivoId)}/completar`,
        cuerpo,
      ),
    );
  }

  /** Quita un archivo pendiente: aborta el multipart o borra el objeto simple. */
  async cancelar(archivoId: string): Promise<void> {
    await firstValueFrom(this.http.delete<void>(`/api/subidas/${encodeURIComponent(archivoId)}`));
  }
}
