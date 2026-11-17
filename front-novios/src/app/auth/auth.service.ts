import { Injectable, inject, signal } from '@angular/core';
import { Api } from '../api/api';

/** Claves de `sessionStorage` (contracts/ui.md): se pierden al cerrar la pestaña (research R6). */
export const CLAVE_TOKEN = 'boda.token';
export const CLAVE_NOMBRE = 'boda.nombreVisible';

/**
 * `sessionStorage` puede lanzar (almacenamiento bloqueado, modo privado). Si falla, la sesión
 * vale solo mientras la página siga abierta: se mantiene también en memoria.
 */
function leer(clave: string): string | null {
  try {
    return sessionStorage.getItem(clave);
  } catch {
    return null;
  }
}

function escribir(clave: string, valor: string | null): void {
  try {
    if (valor === null) sessionStorage.removeItem(clave);
    else sessionStorage.setItem(clave, valor);
  } catch {
    // Sin almacenamiento: queda solo en memoria.
  }
}

/** Sesión de uno de los novios: login, token y logout (FR-023, FR-030). */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly api = inject(Api);
  private tokenEnMemoria: string | null = leer(CLAVE_TOKEN);
  readonly nombreVisible = signal<string | null>(leer(CLAVE_NOMBRE));

  token(): string | null {
    return this.tokenEnMemoria;
  }

  haySesion(): boolean {
    return this.tokenEnMemoria !== null;
  }

  /** Lanza `ApiError` (`CREDENCIALES_INVALIDAS`, `SIN_CONEXION`...) si no se pudo entrar. */
  async entrar(usuario: string, contrasena: string): Promise<void> {
    const sesion = await this.api.login(usuario, contrasena);
    this.tokenEnMemoria = sesion.token;
    escribir(CLAVE_TOKEN, sesion.token);
    escribir(CLAVE_NOMBRE, sesion.nombreVisible);
    this.nombreVisible.set(sesion.nombreVisible);
  }

  /** Invalida el token en el servidor (si se puede) y lo borra del navegador siempre. */
  async salir(): Promise<void> {
    try {
      if (this.haySesion()) await this.api.logout();
    } catch {
      // Sin conexión o sesión ya vencida: igual se borra aquí; el servidor la vence a las 8 h.
    } finally {
      this.olvidar();
    }
  }

  /** Borra la sesión del navegador sin llamar al API (por ejemplo, tras un 401). */
  olvidar(): void {
    this.tokenEnMemoria = null;
    escribir(CLAVE_TOKEN, null);
    escribir(CLAVE_NOMBRE, null);
    this.nombreVisible.set(null);
  }
}
