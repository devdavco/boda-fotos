import { Injectable, inject, signal } from '@angular/core';
import { Api, ApiError, ElementoGaleria, Resumen, aApiError } from '../api/api';

/**
 * Estado de la galería compartido entre `/` y `/archivo/:id`: al volver de la vista completa se
 * conservan las páginas ya cargadas (no se vuelve a empezar desde arriba) y la vista completa
 * conoce el tipo y el nombre del archivo sin pedirlos de nuevo.
 */
@Injectable({ providedIn: 'root' })
export class GaleriaEstado {
  private readonly api = inject(Api);

  readonly resumen = signal<Resumen | null>(null);
  readonly elementos = signal<ElementoGaleria[]>([]);
  readonly cargando = signal(false);
  /** `true` cuando ya no hay más páginas. */
  readonly completa = signal(false);
  readonly error = signal<ApiError | null>(null);
  /** Mensaje para mostrar una vez en la galería (por ejemplo, tras eliminar). */
  readonly aviso = signal<string | null>(null);

  private cursor: string | null = null;
  private iniciada = false;
  /**
   * Cambia en cada `reiniciar()`: una respuesta que llega después de cerrar sesión (o de un 401)
   * pertenece a la sesión anterior y se descarta en vez de mezclarse con la nueva.
   */
  private generacion = 0;

  /** Carga el resumen y la primera página solo la primera vez. */
  async iniciar(): Promise<void> {
    if (this.iniciada) {
      void this.actualizarResumen();
      return;
    }
    this.iniciada = true;
    await Promise.all([this.actualizarResumen(), this.cargarMas()]);
  }

  async actualizarResumen(): Promise<void> {
    const generacion = this.generacion;
    try {
      const resumen = await this.api.resumen();
      if (generacion === this.generacion) this.resumen.set(resumen);
    } catch (e) {
      if (generacion === this.generacion) this.error.set(aApiError(e));
    }
  }

  /** Siguiente página de 24 (FR-026). No hace nada si ya hay una carga en curso o no hay más. */
  async cargarMas(): Promise<void> {
    if (this.cargando() || this.completa()) return;
    const generacion = this.generacion;
    this.cargando.set(true);
    this.error.set(null);
    try {
      const pagina = await this.api.galeria(this.cursor);
      if (generacion !== this.generacion) return;
      this.elementos.update((actuales) => [...actuales, ...pagina.elementos]);
      this.cursor = pagina.siguienteCursor;
      this.completa.set(pagina.siguienteCursor === null);
    } catch (e) {
      if (generacion === this.generacion) this.error.set(aApiError(e));
    } finally {
      if (generacion === this.generacion) this.cargando.set(false);
    }
  }

  buscar(archivoId: string): ElementoGaleria | undefined {
    return this.elementos().find((e) => e.archivoId === archivoId);
  }

  /** Quita un archivo eliminado de la lista y refresca los contadores. */
  quitar(archivoId: string): void {
    this.elementos.update((actuales) => actuales.filter((e) => e.archivoId !== archivoId));
    void this.actualizarResumen();
  }

  /** Al cerrar sesión o al vencer la sesión (401): la siguiente sesión empieza de cero. */
  reiniciar(): void {
    this.generacion++;
    this.cargando.set(false);
    this.resumen.set(null);
    this.elementos.set([]);
    this.completa.set(false);
    this.error.set(null);
    this.aviso.set(null);
    this.cursor = null;
    this.iniciada = false;
  }
}
