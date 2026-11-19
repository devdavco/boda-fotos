import { HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { Api, EstadoRecepcion, aApiError } from '../api/api';

type Estado = EstadoRecepcion['estado'];

/**
 * Estado de la recepción que ve el invitado (contracts/ui.md; FR-013, FR-016). El servidor manda
 * (Principio III): al abrir la página se consulta `GET /api/recepcion/estado` y, si durante una
 * subida cualquier llamada del API responde `RECEPCION_CERRADA` (o `RECEPCION_NO_INICIADA`), la
 * página pasa a ese estado y deja de mostrar el selector de archivos.
 */
@Injectable({ providedIn: 'root' })
export class EstadoRecepcionService {
  private readonly api = inject(Api);

  /** `null` mientras no se conoce. */
  readonly estado = signal<Estado | null>(null);
  /** Mensaje si no se pudo consultar el estado. */
  readonly error = signal<string | null>(null);

  async consultar(): Promise<void> {
    this.error.set(null);
    try {
      this.estado.set((await this.api.estado()).estado);
    } catch {
      this.error.set('No se pudo consultar el estado. Inténtalo de nuevo en unos segundos.');
    }
  }

  /** El API dijo que la recepción ya no está abierta: se refleja en la página. */
  marcar(estado: Exclude<Estado, 'ABIERTA'>): void {
    this.estado.set(estado);
  }
}

/**
 * Observa las respuestas de error del API. No cambia la petición ni el error: la cola de subida
 * sigue recibiendo el mismo `ApiError` y marca el archivo como rechazado, como antes.
 */
export const recepcionInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) return next(req);
  const estado = inject(EstadoRecepcionService);
  return next(req).pipe(
    catchError((error: unknown) => {
      const codigo = aApiError(error).codigo;
      if (codigo === 'RECEPCION_CERRADA') estado.marcar('CERRADA');
      else if (codigo === 'RECEPCION_NO_INICIADA') estado.marcar('NO_INICIADA');
      return throwError(() => error);
    }),
  );
};
