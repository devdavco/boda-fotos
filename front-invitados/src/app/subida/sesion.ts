import { Injectable, inject } from '@angular/core';
import {
  Api,
  ApiError,
  CLAVE_NOMBRE,
  borrarGuardado,
  borrarSesionId,
  guardar,
  guardarSesionId,
  leerGuardado,
  leerSesionId,
} from '../api/api';

/**
 * Sesión de subida del celular (FR-004a, research R8): el servidor emite un `sesionId` que se
 * guarda en `localStorage` junto con el nombre (`boda.sesionId`, `boda.nombre`), así un invitado que
 * cierra y vuelve a abrir la página sigue siendo la misma sesión y el tope de 300 archivos o 5 GB
 * se cuenta desde este celular.
 *
 * La sesión se crea al primer intento de subir (no al abrir la página ni al tocar el botón: el
 * selector de archivos debe abrirse dentro del toque, sin esperar al API, o iOS lo bloquea).
 */
@Injectable({ providedIn: 'root' })
export class Sesion {
  private readonly api = inject(Api);
  private creando: Promise<void> | null = null;

  /** Nombre recordado para prellenar el formulario; vacío si no hay. */
  nombreGuardado(): string {
    return leerGuardado(CLAVE_NOMBRE) ?? '';
  }

  guardarNombre(nombre: string): void {
    guardar(CLAVE_NOMBRE, nombre);
  }

  /** Crea la sesión si no hay una guardada. Varias llamadas a la vez comparten una sola creación. */
  async asegurar(nombre: string): Promise<void> {
    if (leerSesionId() !== null) return;
    await this.crear(nombre);
  }

  /**
   * Ejecuta una llamada que exige sesión. Si el API responde `SESION_INVALIDA` (sesión vencida o
   * desconocida), borra lo guardado, crea una sesión nueva con el nombre actual y repite la llamada
   * una vez. Los archivos autorizados en la sesión anterior ya no existen para la nueva: quien llama
   * debe tratar `NO_ENCONTRADO` como "autorizar de nuevo".
   */
  async conSesion<T>(nombre: string, operacion: () => Promise<T>): Promise<T> {
    await this.asegurar(nombre);
    const usada = leerSesionId();
    try {
      return await operacion();
    } catch (error) {
      if (!(error instanceof ApiError) || error.codigo !== 'SESION_INVALIDA') throw error;
      // Si otra llamada en paralelo ya renovó la sesión, solo se repite con la nueva.
      if (leerSesionId() === usada) {
        borrarSesionId();
        borrarGuardado(CLAVE_NOMBRE);
        await this.crear(nombre);
      } else if (this.creando !== null) {
        await this.creando;
      }
      return await operacion();
    }
  }

  private crear(nombre: string): Promise<void> {
    this.creando ??= (async () => {
      try {
        const sesion = await this.api.crearSesion(nombre);
        guardarSesionId(sesion.sesionId);
        guardar(CLAVE_NOMBRE, nombre);
      } finally {
        this.creando = null;
      }
    })();
    return this.creando;
  }
}
