import { Component, inject, signal } from '@angular/core';
import { Api, EstadoRecepcion } from './api/api';
import { Spike } from './spike';
import { Subida } from './subida/subida';

/**
 * Página única de los invitados (FR-001). Al abrir consulta `GET /api/recepcion/estado` con una
 * ruta relativa (en producción la reenvía el rewrite de Amplify; en desarrollo, `proxy.conf.json`)
 * y solo muestra la pantalla de subida si la recepción está abierta; antes de abrir y después de
 * cerrar muestra el aviso de `contracts/ui.md`, sin selector de archivos.
 */
@Component({
  selector: 'app-root',
  imports: [Spike, Subida],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly api = inject(Api);
  protected readonly estado = signal<EstadoRecepcion['estado'] | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Pantalla temporal del spike de R2: solo con `#spike` en la URL (se elimina en T094). */
  protected readonly spike = location.hash === '#spike';

  constructor() {
    void this.consultar();
  }

  protected async consultar(): Promise<void> {
    this.error.set(null);
    try {
      this.estado.set((await this.api.estado()).estado);
    } catch {
      this.error.set('No se pudo consultar el estado. Inténtalo de nuevo en unos segundos.');
    }
  }
}
