import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, ElementoGaleria, aApiError, esImagenVisible } from '../api/api';
import { MENSAJE_PERIODO_TERMINADO, formatoFechaLarga } from '../galeria/formato';
import { GaleriaEstado } from '../galeria/galeria-estado';

const CONFIRMAR_ELIMINAR = '¿Eliminar este archivo? No se puede recuperar.';
const MENSAJE_FALLO_VISTA =
  'No se pudo mostrar este archivo aquí. Vuelve a abrirlo o descárgalo para verlo.';
const MENSAJE_SIN_VISTA = 'Este formato no se puede previsualizar aquí; descárgalo para verlo.';

/**
 * Vista completa de un archivo (contracts/ui.md, `/archivo/:id`; FR-027, FR-028): `<img>` para
 * JPG, PNG y WEBP, `<video controls playsinline>` para MP4 y MOV y un aviso para HEIC.
 *
 * "Descargar original" no hace `fetch` del archivo (por eso S3 no necesita CORS de lectura): pide
 * al API una URL de `uso=descargar` en el momento del toque y navega a ella; S3 responde con
 * `Content-Disposition: attachment` y el nombre original. Pedirla al tocar evita usar una URL
 * vencida si la pantalla queda abierta más de 15 minutos.
 */
@Component({
  selector: 'app-archivo',
  imports: [RouterLink],
  templateUrl: './archivo.html',
  styleUrl: './archivo.css',
})
export class Archivo implements OnInit {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly galeria = inject(GaleriaEstado);

  /** Parámetro `:id` de la ruta (`withComponentInputBinding`). */
  readonly id = input.required<string>();

  /**
   * Datos del archivo tomados de la galería ya cargada. Si se abrió el enlace directamente (o se
   * recargó la página) no hay datos: se ofrecen igual descargar y eliminar.
   */
  protected readonly elemento = signal<ElementoGaleria | null>(null);
  protected readonly urlVer = signal<string | null>(null);
  protected readonly ocupado = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly mensajeSinVista = MENSAJE_SIN_VISTA;
  protected readonly mensajeFalloVista = MENSAJE_FALLO_VISTA;
  /** El navegador no pudo cargar la imagen o el video (URL vencida, formato no soportado...). */
  protected readonly falloVista = signal(false);

  protected readonly vista = computed<'imagen' | 'video' | 'ninguna' | 'desconocida'>(() => {
    const e = this.elemento();
    if (!e) return 'desconocida';
    if (e.tipo === 'VIDEO') return 'video';
    return esImagenVisible(e.formato) ? 'imagen' : 'ninguna';
  });
  protected readonly fecha = computed(() => {
    const e = this.elemento();
    return e ? formatoFechaLarga(e.subidoEn) : null;
  });

  ngOnInit(): void {
    this.elemento.set(this.galeria.buscar(this.id()) ?? null);
    const vista = this.vista();
    if (vista === 'imagen' || vista === 'video') void this.cargarVista();
  }

  private async cargarVista(): Promise<void> {
    try {
      this.urlVer.set((await this.api.url(this.id(), 'ver')).url);
    } catch (e) {
      this.mostrarError(e);
    }
  }

  protected async descargar(): Promise<void> {
    if (this.ocupado()) return;
    this.ocupado.set(true);
    this.error.set(null);
    try {
      const { url } = await this.api.url(this.id(), 'descargar');
      window.location.assign(url);
    } catch (e) {
      this.mostrarError(e);
    } finally {
      this.ocupado.set(false);
    }
  }

  protected async eliminar(): Promise<void> {
    if (this.ocupado() || !confirm(CONFIRMAR_ELIMINAR)) return;
    this.ocupado.set(true);
    this.error.set(null);
    try {
      await this.api.eliminar(this.id());
      this.galeria.quitar(this.id());
      await this.router.navigateByUrl('/');
    } catch (e) {
      this.mostrarError(e);
    } finally {
      this.ocupado.set(false);
    }
  }

  /**
   * `NO_ENCONTRADO` (por ejemplo, el otro novio ya lo eliminó): se muestra el mensaje del API en
   * la galería y se vuelve a ella. `PERIODO_TERMINADO`: el mensaje de fin de periodo.
   */
  private mostrarError(e: unknown): void {
    const error = aApiError(e);
    if (error.codigo === 'NO_ENCONTRADO') {
      this.galeria.quitar(this.id());
      this.galeria.aviso.set(error.mensaje);
      void this.router.navigateByUrl('/');
      return;
    }
    if (error.estado === 401) return; // el interceptor ya volvió a /entrar
    this.error.set(error.codigo === 'PERIODO_TERMINADO' ? MENSAJE_PERIODO_TERMINADO : error.mensaje);
  }
}
