import { Component, signal } from '@angular/core';

/**
 * Pantalla TEMPORAL del spike de R2 (T027): sube un archivo con `PUT` a una URL prefirmada de S3
 * pegada a mano, usando XMLHttpRequest (es lo que usará la subida real: `fetch` no informa el
 * progreso de subida). Se muestra solo con `#spike` en la URL. Se elimina en T094.
 *
 * Qué comprueba el spike:
 * - que el PUT directo navegador -> S3 funciona en Safari iOS y Chrome Android con datos móviles;
 * - que el ETag de la respuesta es legible desde JavaScript (ExposeHeaders del CORS);
 * - si S3 rechaza un archivo de tamaño distinto al firmado (K9, research R2).
 */
@Component({
  selector: 'app-spike',
  template: `
    <section>
      <h2>Spike de subida directa a S3 (temporal)</h2>

      <label>
        URL prefirmada (PUT)
        <textarea rows="5" [value]="url()" (input)="url.set(texto($event))"></textarea>
      </label>

      <label>
        Content-Type con el que se firmó
        <input type="text" [value]="tipo()" (input)="tipo.set(texto($event))" />
      </label>

      <label>
        Archivo
        <input type="file" (change)="elegir($event)" />
      </label>

      @if (archivo(); as f) {
        <p class="dato">
          Elegido: {{ f.name }} · {{ f.size }} bytes · tipo del navegador:
          {{ f.type || '(vacío)' }}
        </p>
      }

      <button type="button" (click)="subir()" [disabled]="!puedeSubir()">Subir</button>

      @if (progreso() !== null) {
        <progress max="100" [value]="progreso()"></progress>
        <p class="dato">Progreso: {{ progreso() }} %</p>
      }

      @if (resultado(); as r) {
        <pre>{{ r }}</pre>
      }
    </section>
  `,
  styles: `
    section {
      display: grid;
      gap: 0.75rem;
      margin-top: 1.5rem;
      padding-top: 1rem;
      border-top: 1px solid #ccc;
    }
    label {
      display: grid;
      gap: 0.25rem;
    }
    textarea,
    input[type='text'] {
      width: 100%;
      box-sizing: border-box;
      font-size: 1rem;
    }
    button {
      padding: 0.75rem;
      font-size: 1rem;
    }
    progress {
      width: 100%;
    }
    .dato {
      margin: 0;
      font-size: 0.9rem;
    }
    pre {
      white-space: pre-wrap;
      word-break: break-all;
      font-size: 0.85rem;
      margin: 0;
    }
  `,
})
export class Spike {
  protected readonly url = signal('');
  protected readonly tipo = signal('image/jpeg');
  protected readonly archivo = signal<File | null>(null);
  protected readonly progreso = signal<number | null>(null);
  protected readonly resultado = signal<string | null>(null);
  private enCurso = false;

  protected texto(evento: Event): string {
    return (evento.target as HTMLInputElement | HTMLTextAreaElement).value.trim();
  }

  protected elegir(evento: Event): void {
    this.archivo.set((evento.target as HTMLInputElement).files?.item(0) ?? null);
    this.progreso.set(null);
    this.resultado.set(null);
  }

  protected puedeSubir(): boolean {
    return this.url() !== '' && this.tipo() !== '' && this.archivo() !== null;
  }

  protected subir(): void {
    const archivo = this.archivo();
    if (archivo === null || this.enCurso) return;
    this.enCurso = true;
    this.progreso.set(0);
    this.resultado.set(null);

    const inicio = performance.now();
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', this.url());
    // El Content-Type debe ser exactamente el que se usó al firmar; si no, S3 responde 403.
    xhr.setRequestHeader('Content-Type', this.tipo());

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) this.progreso.set(Math.round((100 * e.loaded) / e.total));
    };

    xhr.onload = () => {
      this.enCurso = false;
      const segundos = ((performance.now() - inicio) / 1000).toFixed(1);
      const exito = xhr.status >= 200 && xhr.status < 300;
      if (exito) this.progreso.set(100);
      const etag = xhr.getResponseHeader('ETag');
      const lineas = [
        `HTTP ${xhr.status} ${xhr.statusText}`.trim(),
        `ETag legible por JavaScript: ${etag ?? '(no visible: revisar ExposeHeaders del CORS)'}`,
        `Bytes enviados: ${archivo.size} en ${segundos} s`,
        `Encabezados visibles: ${xhr.getAllResponseHeaders().trim().replace(/\r?\n/g, ' | ') || '(ninguno)'}`,
      ];
      if (!exito && xhr.responseText) {
        lineas.push(`Respuesta de S3: ${xhr.responseText.slice(0, 600)}`);
      }
      this.resultado.set(lineas.join('\n'));
    };

    // status 0: sin red, o bloqueado por CORS (el navegador no da más detalle).
    xhr.onerror = () => this.fallar('Error de red o CORS (HTTP 0)');
    xhr.ontimeout = () => this.fallar('Tiempo de espera agotado');
    xhr.onabort = () => this.fallar('Subida cancelada');

    xhr.send(archivo);
  }

  private fallar(motivo: string): void {
    this.enCurso = false;
    this.progreso.set(null);
    this.resultado.set(`Falló: ${motivo}`);
  }
}
