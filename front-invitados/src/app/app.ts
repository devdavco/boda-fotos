import { Component, inject } from '@angular/core';
import { EstadoRecepcionService } from './estado/estado';
import { Spike } from './spike';
import { Subida } from './subida/subida';

/**
 * Página única de los invitados (FR-001). Al abrir consulta `GET /api/recepcion/estado` con una
 * ruta relativa (en producción la reenvía el rewrite de Amplify; en desarrollo, `proxy.conf.json`)
 * y solo muestra la pantalla de subida si la recepción está abierta; antes de abrir y después de
 * cerrar muestra el aviso de `contracts/ui.md`, sin selector de archivos. Si la recepción cierra
 * mientras hay subidas en curso, `EstadoRecepcionService` cambia el estado y aparece el aviso (T062).
 */
@Component({
  selector: 'app-root',
  imports: [Spike, Subida],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly recepcion = inject(EstadoRecepcionService);
  protected readonly estado = this.recepcion.estado;
  protected readonly error = this.recepcion.error;
  /** Pantalla temporal del spike de R2: solo con `#spike` en la URL (se elimina en T094). */
  protected readonly spike = location.hash === '#spike';

  constructor() {
    void this.consultar();
  }

  protected consultar(): Promise<void> {
    return this.recepcion.consultar();
  }
}
