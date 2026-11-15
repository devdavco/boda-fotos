import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { Api, aApiError } from '../api/api';
import { Auth } from '../auth/auth.service';
import { Celda } from './celda';
import {
  MENSAJE_PERIODO_TERMINADO,
  formatoFechaLarga,
  formatoNumero,
  periodoTerminadoSegunFecha,
} from './formato';
import { GaleriaEstado } from './galeria-estado';

const CONFIRMAR_CIERRE =
  '¿Cerrar la recepción? Los invitados ya no podrán subir más fotos ni videos. Esto no se puede deshacer.';

/**
 * Galería de los novios (contracts/ui.md, FR-026, FR-029, FR-030, FR-030b, FR-032): cabecera con
 * el contador y el estado de la recepción, banner con la fecha límite y cuadrícula del más
 * reciente al más antiguo, de 24 en 24 al acercarse al final. Sin filtros ni búsqueda (FR-034).
 */
@Component({
  selector: 'app-galeria',
  imports: [Celda],
  templateUrl: './galeria.html',
  styleUrl: './galeria.css',
})
export class Galeria {
  private readonly api = inject(Api);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  protected readonly estado = inject(GaleriaEstado);
  private readonly fin = viewChild<ElementRef<HTMLElement>>('fin');

  protected readonly nombre = this.auth.nombreVisible;
  protected readonly cerrando = signal(false);
  protected readonly errorCierre = signal<string | null>(null);

  /** Por fecha (backend ya apagado) o porque el API respondió `PERIODO_TERMINADO` (FR-032). */
  protected readonly periodoTerminado = computed(
    () =>
      periodoTerminadoSegunFecha() ||
      this.estado.resumen()?.periodoTerminado === true ||
      this.estado.error()?.codigo === 'PERIODO_TERMINADO',
  );
  protected readonly mensajePeriodo = MENSAJE_PERIODO_TERMINADO;

  /** "1.240 archivos (1.102 fotos, 138 videos)". */
  protected readonly contador = computed(() => {
    const r = this.estado.resumen();
    if (!r) return null;
    const archivos = r.archivosTotal === 1 ? 'archivo' : 'archivos';
    const fotos = r.fotos === 1 ? 'foto' : 'fotos';
    const videos = r.videos === 1 ? 'video' : 'videos';
    return `${formatoNumero(r.archivosTotal)} ${archivos} (${formatoNumero(r.fotos)} ${fotos}, ${formatoNumero(r.videos)} ${videos})`;
  });
  protected readonly ultimaSubida = computed(() => {
    const iso = this.estado.resumen()?.ultimaSubidaEn;
    return iso ? formatoFechaLarga(iso) : null;
  });
  protected readonly recepcion = computed(() => {
    const r = this.estado.resumen()?.recepcion;
    if (!r) return null;
    switch (r.estado) {
      case 'ABIERTA':
        // La hora ya termina en "p. m." o "a. m.": sin punto final para no duplicarlo.
        return `La recepción está abierta hasta el ${formatoFechaLarga(r.cierreEn)}`;
      case 'NO_INICIADA':
        return 'La recepción abre el jueves 19 de noviembre de 2026.';
      case 'CERRADA':
        return 'La recepción está cerrada: los invitados ya no pueden subir fotos ni videos.';
    }
  });
  protected readonly puedeCerrar = computed(
    () => this.estado.resumen() !== null && this.estado.resumen()?.recepcion.estado !== 'CERRADA',
  );
  /** Error de la galería que no sea el de periodo terminado (ese tiene su propio mensaje). */
  protected readonly errorCarga = computed(() => {
    const e = this.estado.error();
    return e && e.codigo !== 'PERIODO_TERMINADO' ? e.mensaje : null;
  });

  constructor() {
    if (!periodoTerminadoSegunFecha()) void this.estado.iniciar();

    // Scroll infinito: un centinela al final de la cuadrícula pide la siguiente página cuando se
    // acerca a la pantalla. Se vuelve a observar tras cada página, porque si el centinela sigue
    // visible (pantallas grandes) el observador no avisa de nuevo por sí solo.
    const destroyRef = inject(DestroyRef);
    let observador: IntersectionObserver | null = null;
    afterNextRender(() => {
      if (typeof IntersectionObserver === 'undefined') return;
      observador = new IntersectionObserver(
        (entradas) => {
          if (entradas.some((e) => e.isIntersecting)) void this.estado.cargarMas();
        },
        { rootMargin: '600px' },
      );
      destroyRef.onDestroy(() => observador?.disconnect());
    });
    effect(() => {
      this.estado.elementos();
      const fin = this.fin()?.nativeElement;
      if (this.estado.cargando() || !observador || !fin) return;
      observador.disconnect();
      observador.observe(fin);
    });
  }

  protected async cerrarRecepcion(): Promise<void> {
    if (!confirm(CONFIRMAR_CIERRE)) return;
    this.cerrando.set(true);
    this.errorCierre.set(null);
    try {
      await this.api.cerrarRecepcion();
      await this.estado.actualizarResumen();
    } catch (e) {
      this.errorCierre.set(aApiError(e).mensaje);
    } finally {
      this.cerrando.set(false);
    }
  }

  protected async salir(): Promise<void> {
    await this.auth.salir();
    this.estado.reiniciar();
    await this.router.navigateByUrl('/entrar');
  }

  protected reintentar(): void {
    void this.estado.cargarMas();
  }
}
