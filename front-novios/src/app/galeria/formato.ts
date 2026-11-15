/** Formatos de fecha y número en español de Colombia y hora de Cali (Principio VI). */

const ZONA = 'America/Bogota';

const numero = new Intl.NumberFormat('es-CO');

/** "sábado 21, 9:41 p. m." (cabecera de la galería). */
const fechaLarga = new Intl.DateTimeFormat('es-CO', {
  timeZone: ZONA,
  weekday: 'long',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

/** "21 de nov, 9:41 p. m." (cada celda). */
const fechaCorta = new Intl.DateTimeFormat('es-CO', {
  timeZone: ZONA,
  day: 'numeric',
  month: 'short',
  hour: 'numeric',
  minute: '2-digit',
});

export function formatoNumero(n: number): string {
  return numero.format(n);
}

export function formatoFechaLarga(iso: string): string {
  return fechaLarga.format(new Date(iso));
}

export function formatoFechaCorta(iso: string): string {
  return fechaCorta.format(new Date(iso));
}

/**
 * Fin del periodo para ver y descargar: 2026-12-01 23:59:59 en Cali (FR-032). Después, el front
 * muestra el mensaje de periodo terminado aunque el backend ya esté apagado (contracts/ui.md).
 */
export const FIN_DISPONIBILIDAD = new Date('2026-12-02T04:59:59Z');

export const MENSAJE_PERIODO_TERMINADO =
  'El periodo para ver y descargar terminó el 1 de diciembre de 2026. Para recuperar el material, escribe al responsable técnico.';

export function periodoTerminadoSegunFecha(ahora = new Date()): boolean {
  return ahora.getTime() > FIN_DISPONIBILIDAD.getTime();
}
