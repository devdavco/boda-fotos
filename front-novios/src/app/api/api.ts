import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/**
 * Cliente tipado del API de administración (contracts/api.md, "API de administración").
 *
 * Todas las llamadas usan rutas relativas (`/api/...`): en producción las reenvía el rewrite de
 * Amplify y en desarrollo `proxy.conf.json`. El token lo agrega `auth.interceptor.ts`.
 */

/**
 * Error del API: `mensaje` está en español y se muestra tal cual (Principio VI). `codigo` es el
 * del contrato (`NO_ENCONTRADO`, `PERIODO_TERMINADO`...) o uno local (`SIN_CONEXION`).
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
    if (error.status === 0) return new ApiError('SIN_CONEXION', MENSAJE_SIN_CONEXION, 0);
    return new ApiError('ERROR_INTERNO', MENSAJE_ERROR_INTERNO, error.status);
  }
  return new ApiError('ERROR_INTERNO', MENSAJE_ERROR_INTERNO, 0);
}

export interface SesionIniciada {
  token: string;
  usuario: string;
  nombreVisible: string;
  inactividadMaxSegundos: number;
}

export type EstadoRecepcion = 'NO_INICIADA' | 'ABIERTA' | 'CERRADA';

export interface Resumen {
  archivosTotal: number;
  fotos: number;
  videos: number;
  ultimaSubidaEn: string | null;
  recepcion: { estado: EstadoRecepcion; cierreEn: string };
  disponibleHasta: string;
  periodoTerminado: boolean;
}

export type Formato = 'jpg' | 'png' | 'heic' | 'webp' | 'mp4' | 'mov';

export interface ElementoGaleria {
  archivoId: string;
  tipo: 'FOTO' | 'VIDEO';
  formato: Formato;
  nombreInvitado: string;
  nombreOriginal: string;
  tamano: number;
  subidoEn: string;
  /** Prefirmada de 15 minutos, o `null` si el archivo no tiene miniatura. */
  urlMiniatura: string | null;
}

export interface PaginaGaleria {
  elementos: ElementoGaleria[];
  /** `null` en la última página. */
  siguienteCursor: string | null;
}

export interface UrlArchivo {
  url: string;
  expiraEn: string;
}

export interface RecepcionCerrada {
  estado: EstadoRecepcion;
  cierreEn: string;
  cerradaPor: string | null;
}

/** Formatos que el navegador puede mostrar con `<img>` (HEIC no, research R4). */
export function esImagenVisible(formato: Formato): boolean {
  return formato === 'jpg' || formato === 'png' || formato === 'webp';
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  login(usuario: string, contrasena: string): Promise<SesionIniciada> {
    return firstValueFrom(
      this.http.post<SesionIniciada>('/api/admin/login', { usuario, contrasena }),
    );
  }

  async logout(): Promise<void> {
    await firstValueFrom(this.http.post<void>('/api/admin/logout', null));
  }

  resumen(): Promise<Resumen> {
    return firstValueFrom(this.http.get<Resumen>('/api/admin/resumen'));
  }

  galeria(cursor: string | null, limite = 24): Promise<PaginaGaleria> {
    let params = new HttpParams().set('limite', limite);
    if (cursor) params = params.set('cursor', cursor);
    return firstValueFrom(this.http.get<PaginaGaleria>('/api/admin/galeria', { params }));
  }

  /** URL prefirmada de 15 minutos del original, para verlo o para descargarlo con su nombre. */
  url(archivoId: string, uso: 'ver' | 'descargar'): Promise<UrlArchivo> {
    return firstValueFrom(
      this.http.get<UrlArchivo>(`/api/admin/archivos/${encodeURIComponent(archivoId)}/url`, {
        params: { uso },
      }),
    );
  }

  async eliminar(archivoId: string): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(`/api/admin/archivos/${encodeURIComponent(archivoId)}`),
    );
  }

  cerrarRecepcion(): Promise<RecepcionCerrada> {
    return firstValueFrom(this.http.post<RecepcionCerrada>('/api/admin/recepcion/cerrar', null));
  }
}
