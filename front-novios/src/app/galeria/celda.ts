import {
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, ElementoGaleria, esImagenVisible } from '../api/api';
import { formatoFechaCorta } from './formato';

/**
 * Una celda de la cuadrícula (contracts/ui.md): miniatura, nombre del invitado y fecha en hora de
 * Cali. Sin miniatura, HEIC y videos muestran el ícono de su tipo; JPG, PNG y WEBP cargan el
 * original, pero solo cuando la celda se acerca a la pantalla (research R4), para no pedir una
 * URL por cada archivo de la página.
 */
@Component({
  selector: 'app-celda',
  imports: [RouterLink],
  template: `
    <a [routerLink]="['/archivo', elemento().archivoId]" [attr.aria-label]="etiqueta()">
      @if (src(); as url) {
        <img [src]="url" alt="" loading="lazy" decoding="async" (error)="fallo.set(true)" />
      } @else {
        <span class="icono" aria-hidden="true">
          {{ elemento().tipo === 'VIDEO' ? '🎬' : '📷' }}
          <small>{{ elemento().tipo === 'VIDEO' ? 'Video' : elemento().formato.toUpperCase() }}</small>
        </span>
      }
      @if (elemento().tipo === 'VIDEO' && src()) {
        <span class="marca" aria-hidden="true">▶</span>
      }
    </a>
    <p class="invitado">{{ elemento().nombreInvitado }}</p>
    <p class="fecha">{{ fecha() }}</p>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    a {
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      aspect-ratio: 1;
      overflow: hidden;
      background: #eceff3;
      border-radius: 0.375rem;
      text-decoration: none;
      color: var(--gris);
    }
    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
    .icono {
      display: grid;
      justify-items: center;
      font-size: 1.8rem;
      line-height: 1.2;
    }
    .icono small {
      font-size: 0.7rem;
      font-weight: 600;
    }
    .marca {
      position: absolute;
      right: 0.3rem;
      bottom: 0.3rem;
      padding: 0 0.35rem;
      font-size: 0.75rem;
      color: #fff;
      background: rgb(0 0 0 / 0.55);
      border-radius: 0.25rem;
    }
    p {
      margin: 0;
      overflow: hidden;
      font-size: 0.75rem;
      white-space: nowrap;
      text-overflow: ellipsis;
    }
    .invitado {
      font-weight: 600;
    }
    .fecha {
      color: var(--gris);
    }
  `,
})
export class Celda implements OnInit {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);

  readonly elemento = input.required<ElementoGaleria>();

  /** URL del original pedida al acercarse a la pantalla (solo JPG, PNG y WEBP sin miniatura). */
  private readonly urlOriginal = signal<string | null>(null);
  /**
   * La imagen no cargó: por ejemplo, la URL de la miniatura (15 minutos) venció antes de que la
   * celda llegara a la pantalla. Se muestra el ícono.
   */
  protected readonly fallo = signal(false);

  protected readonly src = computed(() =>
    this.fallo() ? null : (this.elemento().urlMiniatura ?? this.urlOriginal()),
  );
  protected readonly fecha = computed(() => formatoFechaCorta(this.elemento().subidoEn));
  protected readonly etiqueta = computed(() => {
    const e = this.elemento();
    return `${e.tipo === 'VIDEO' ? 'Video' : 'Foto'} de ${e.nombreInvitado}, ${this.fecha()}`;
  });

  ngOnInit(): void {
    const e = this.elemento();
    if (e.urlMiniatura !== null || !esImagenVisible(e.formato)) return;
    if (typeof IntersectionObserver === 'undefined') {
      void this.pedirOriginal();
      return;
    }
    const observador = new IntersectionObserver(
      (entradas) => {
        if (entradas.some((entrada) => entrada.isIntersecting)) {
          observador.disconnect();
          void this.pedirOriginal();
        }
      },
      { rootMargin: '300px' },
    );
    observador.observe(this.host.nativeElement);
    this.destroyRef.onDestroy(() => observador.disconnect());
  }

  private async pedirOriginal(): Promise<void> {
    try {
      this.urlOriginal.set((await this.api.url(this.elemento().archivoId, 'ver')).url);
    } catch {
      // Sin URL se queda el ícono; la vista completa vuelve a intentarlo.
    }
  }
}
