import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';

interface EstadoRecepcion {
  estado: string;
  aperturaEn: string;
  cierreEn: string;
  ahora: string;
}

/**
 * Pantalla mínima del esqueleto: consulta GET /api/recepcion/estado con una ruta relativa
 * (en producción la reenvía el rewrite de Amplify; en desarrollo, proxy.conf.json) y muestra
 * el estado. Sirve para el spike de R1. La pantalla de subida la reemplaza (T050).
 */
@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly http = inject(HttpClient);

  protected readonly estado = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.http.get<EstadoRecepcion>('/api/recepcion/estado').subscribe({
      next: (respuesta) => this.estado.set(respuesta.estado),
      error: () =>
        this.error.set('No se pudo consultar el estado. Inténtalo de nuevo en unos segundos.'),
    });
  }
}
