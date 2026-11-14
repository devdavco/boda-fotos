import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { ColaSubida, ItemSubida } from './cola-subida';
import { Sesion } from './sesion';

const MAX_NOMBRE = 60;
const MENSAJE_NOMBRE = 'Escribe tu nombre (máximo 60 caracteres).';

const formatoNumero = new Intl.NumberFormat('es-CO', { maximumFractionDigits: 1 });

/** Tamaño legible, por ejemplo "3,2 MB". */
export function tamanoLegible(bytes: number): string {
  if (bytes < 1024 * 1024) return `${formatoNumero.format(Math.max(bytes, 0) / 1024)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${formatoNumero.format(bytes / (1024 * 1024))} MB`;
  return `${formatoNumero.format(bytes / (1024 * 1024 * 1024))} GB`;
}

/**
 * Pantalla de subida de los invitados (contracts/ui.md, FR-003 a FR-008): aviso de uso de las
 * fotos, nombre, selector de archivos y lista con el avance de cada uno.
 */
@Component({
  selector: 'app-subida',
  templateUrl: './subida.html',
  styleUrl: './subida.css',
})
export class Subida {
  private readonly sesion = inject(Sesion);
  protected readonly cola = inject(ColaSubida);
  private readonly selector = viewChild.required<ElementRef<HTMLInputElement>>('selector');
  private readonly campoNombre = viewChild.required<ElementRef<HTMLInputElement>>('campoNombre');

  protected readonly mensajeNombre = MENSAJE_NOMBRE;
  protected readonly nombre = signal(this.sesion.nombreGuardado());
  /** Se activa al tocar "Elegir fotos y videos" con el nombre inválido. */
  private readonly intentoSinNombre = signal(false);
  protected readonly nombreValido = computed(() => {
    const largo = this.nombre().trim().length;
    return largo >= 1 && largo <= MAX_NOMBRE;
  });
  protected readonly errorNombre = computed(() => this.intentoSinNombre() && !this.nombreValido());
  /** Todo terminó bien: nada en curso ni fallido y al menos un archivo subido. */
  protected readonly terminado = computed(
    () => this.cola.pendientes() === 0 && this.cola.fallidos() === 0 && this.cola.listos() > 0,
  );

  protected escribirNombre(evento: Event): void {
    this.nombre.set((evento.target as HTMLInputElement).value);
  }

  /**
   * Abre el selector dentro del mismo toque: si se esperara al API antes de abrirlo, iOS lo
   * bloquearía. La sesión se crea al autorizar el primer archivo (`Sesion`).
   */
  protected elegir(): void {
    if (!this.nombreValido()) {
      this.intentoSinNombre.set(true);
      this.campoNombre().nativeElement.focus();
      return;
    }
    this.selector().nativeElement.click();
  }

  protected alElegir(evento: Event): void {
    const campo = evento.target as HTMLInputElement;
    const archivos = Array.from(campo.files ?? []);
    // Se vacía para que elegir el mismo archivo otra vez vuelva a disparar el evento.
    campo.value = '';
    if (archivos.length === 0 || !this.nombreValido()) return;
    const nombre = this.nombre().trim();
    this.sesion.guardarNombre(nombre);
    this.cola.agregar(archivos, nombre);
  }

  protected reintentar(): void {
    this.cola.reintentarFallidos();
  }

  protected etiqueta(item: ItemSubida): string {
    switch (item.estado()) {
      case 'EN_COLA':
        return 'En cola';
      case 'SUBIENDO':
        return `Subiendo ${item.progreso()} %`;
      case 'LISTO':
        return 'Listo ✓';
      case 'FALLO':
        return `Falló: ${item.mensaje()}`;
      case 'RECHAZADO':
        return `Rechazado: ${item.mensaje()}`;
    }
  }

  protected icono(item: ItemSubida): string {
    if (!item.clasificacion.ok) return '📄';
    return item.clasificacion.tipo === 'VIDEO' ? '🎬' : '🖼️';
  }

  protected tamano(bytes: number): string {
    return tamanoLegible(bytes);
  }

  protected plural(n: number): string {
    return n === 1 ? 'archivo' : 'archivos';
  }
}
