import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import {
  Api,
  ApiError,
  Autorizacion,
  AutorizacionSimple,
  MENSAJE_SIN_CONEXION,
  ParteCompletada,
  PeticionAutorizar,
} from '../api/api';
import { generarMiniatura } from './miniatura';
import { ErrorMultipart, EstadoMultipart, enviarParteXhr, subirPartes } from './multipart';
import { Sesion } from './sesion';
import { Clasificacion, Formato, clasificar } from './tipos';

/** Archivos que se suben a la vez (research R13). */
export const MAX_PARALELO = 3;

/** Máximo de una miniatura (FR-026): el API rechaza una URL de miniatura de más de 1 MiB. */
const MAX_MINIATURA_BYTES = 1_048_576;
/** Tiempo máximo para generar la miniatura; si se pasa, el archivo sube sin ella (FR-026a). */
const TIEMPO_MINIATURA_MS = 5000;
/** Esperas entre los reintentos de `completar`. */
const ESPERAS_COMPLETAR_MS = [1000, 2000, 4000];

const MENSAJE_PUT_FALLIDO = 'No se pudo enviar el archivo. Inténtalo de nuevo.';

/**
 * Genera la miniatura JPEG de un archivo, o `null` si no puede. Se llama **antes** de autorizar: la
 * URL de la miniatura firma su tamaño (research R2 y R4), así que el tamaño debe conocerse al pedir
 * la autorización. La implementación es `miniatura.ts` (T055); es un token para poder
 * reemplazarla en pruebas.
 */
export type Miniaturizador = (archivo: File, formato: Formato) => Promise<Blob | null>;

export const MINIATURIZADOR = new InjectionToken<Miniaturizador>('MINIATURIZADOR', {
  providedIn: 'root',
  factory: () => generarMiniatura,
});

export type EstadoItem = 'EN_COLA' | 'SUBIENDO' | 'LISTO' | 'FALLO' | 'RECHAZADO';

/**
 * Códigos que son una regla y no una falla pasajera: reintentar da lo mismo. Los demás errores
 * (red, servidor, `SUBIDA_INCOMPLETA`) quedan como "Falló" y se pueden reintentar.
 */
const CODIGOS_DE_REGLA = new Set([
  'TIPO_NO_PERMITIDO',
  'ARCHIVO_VACIO',
  'ARCHIVO_MUY_GRANDE',
  'TOPE_SESION',
  'RECEPCION_NO_INICIADA',
  'RECEPCION_CERRADA',
  'CONTENIDO_INCONSISTENTE',
]);

/** Un archivo de la lista. Los datos que cambian son señales para que la fila se actualice sola. */
export class ItemSubida {
  readonly estado = signal<EstadoItem>('EN_COLA');
  /** 0 a 100, del envío del archivo original. */
  readonly progreso = signal(0);
  /** Motivo, en español, cuando el estado es `FALLO` o `RECHAZADO`. */
  readonly mensaje = signal('');
  /** URL local de la miniatura (si se generó), para mostrarla en la fila. */
  readonly vistaPrevia = signal<string | null>(null);

  /** Se guarda al autorizar: los reintentos piden una URL nueva en vez de autorizar otra vez. */
  archivoId: string | null = null;
  miniatura: Blob | null = null;
  miniaturaGenerada = false;
  miniaturaSubida = false;
  /** El original ya está en S3 y falta completar. */
  originalSubido = false;
  /** Solo videos de 100 MB o más: partes ya subidas y sus `ETag`, que sobreviven a un reintento. */
  multipart: EstadoMultipart | null = null;
  /** Partes para `completar` (multipart), en orden. */
  partes: ParteCompletada[] | null = null;

  constructor(
    readonly archivo: File,
    readonly nombreInvitado: string,
    readonly clasificacion: Clasificacion,
  ) {}
}

class ErrorDeItem extends Error {
  constructor(
    readonly estadoFinal: 'FALLO' | 'RECHAZADO',
    readonly mensajeFinal: string,
  ) {
    super(mensajeFinal);
  }
}

/**
 * `PUT` a una URL prefirmada de S3 con `XMLHttpRequest`: `fetch` no informa el progreso de subida
 * (research R13). El navegador calcula `Content-Length` a partir del cuerpo, que es el tamaño que
 * firmó el servidor. Devuelve el estado HTTP, o 0 si no hubo respuesta (red caída, CORS).
 */
function enviarPut(
  url: string,
  encabezados: Record<string, string>,
  cuerpo: Blob,
  alProgreso?: (porcentaje: number) => void,
): Promise<number> {
  return new Promise((resolver) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    for (const [nombre, valor] of Object.entries(encabezados)) {
      xhr.setRequestHeader(nombre, valor);
    }
    if (alProgreso) {
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable && e.total > 0) alProgreso(Math.floor((100 * e.loaded) / e.total));
      };
    }
    xhr.onload = () => resolver(xhr.status);
    xhr.onerror = () => resolver(0);
    xhr.ontimeout = () => resolver(0);
    xhr.onabort = () => resolver(0);
    xhr.send(cuerpo);
  });
}

function esperar(ms: number): Promise<void> {
  return new Promise((resolver) => setTimeout(resolver, ms));
}

function esReintentableAlCompletar(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    (error.codigo === 'SUBIDA_INCOMPLETA' || error.estado === 0 || error.estado >= 500)
  );
}

/**
 * Cola de subida: hasta `MAX_PARALELO` archivos a la vez. Cada archivo se autoriza cuando le llega
 * el turno, no al elegirlo, para que la URL de 5 minutos no venza mientras espera (research R2).
 */
@Injectable({ providedIn: 'root' })
export class ColaSubida {
  private readonly api = inject(Api);
  private readonly sesion = inject(Sesion);
  private readonly miniaturizador = inject(MINIATURIZADOR);
  private enCurso = 0;

  readonly items = signal<ItemSubida[]>([]);
  /** Archivos en cola o subiendo. */
  readonly pendientes = computed(
    () => this.items().filter((i) => i.estado() === 'EN_COLA' || i.estado() === 'SUBIENDO').length,
  );
  readonly listos = computed(() => this.items().filter((i) => i.estado() === 'LISTO').length);
  readonly fallidos = computed(() => this.items().filter((i) => i.estado() === 'FALLO').length);

  /** Agrega los archivos elegidos. Los rechazos locales se muestran sin llamar al API. */
  agregar(archivos: File[], nombreInvitado: string): void {
    const nuevos = archivos.map((archivo) => {
      const clasificacion = clasificar(archivo);
      const item = new ItemSubida(archivo, nombreInvitado, clasificacion);
      if (!clasificacion.ok) {
        this.rechazar(item, clasificacion.mensaje);
      }
      return item;
    });
    this.items.update((actuales) => [...actuales, ...nuevos]);
    this.avanzar();
  }

  /**
   * Vuelve a poner en cola solo los fallidos. Los rechazados por regla no se reintentan. Un video
   * en partes sigue desde las que faltaban, así que su progreso no vuelve a 0.
   */
  reintentarFallidos(): void {
    for (const item of this.items()) {
      if (item.estado() !== 'FALLO') continue;
      item.mensaje.set('');
      if (item.multipart === null) item.progreso.set(0);
      item.estado.set('EN_COLA');
    }
    this.avanzar();
  }

  private rechazar(item: ItemSubida, mensaje: string): void {
    item.mensaje.set(mensaje);
    item.estado.set('RECHAZADO');
  }

  private avanzar(): void {
    while (this.enCurso < MAX_PARALELO) {
      const siguiente = this.items().find((i) => i.estado() === 'EN_COLA');
      if (!siguiente) return;
      siguiente.estado.set('SUBIENDO');
      this.enCurso++;
      void this.procesar(siguiente).finally(() => {
        this.enCurso--;
        this.avanzar();
      });
    }
  }

  private async procesar(item: ItemSubida): Promise<void> {
    try {
      await this.subir(item);
      item.progreso.set(100);
      item.estado.set('LISTO');
    } catch (error) {
      if (error instanceof ErrorDeItem) {
        item.mensaje.set(error.mensajeFinal);
        item.estado.set(error.estadoFinal);
      } else if (error instanceof ApiError) {
        item.mensaje.set(error.mensaje);
        item.estado.set(CODIGOS_DE_REGLA.has(error.codigo) ? 'RECHAZADO' : 'FALLO');
      } else {
        item.mensaje.set(MENSAJE_PUT_FALLIDO);
        item.estado.set('FALLO');
      }
    }
  }

  private async subir(item: ItemSubida): Promise<void> {
    if (!item.originalSubido) {
      // Un multipart ya autorizado sigue con sus partes; si no, se autoriza o se pide URL nueva.
      if (item.multipart === null) {
        let autorizacion: Autorizacion | null = null;
        if (item.archivoId !== null) {
          autorizacion = await this.pedirUrlNueva(item);
        }
        if (autorizacion === null) {
          autorizacion = await this.autorizar(item);
        }
        // La miniatura sube enseguida, antes del original: su URL dura 5 minutos y no se puede
        // renovar, y un video grande por 4G tarda más que eso (research R4). Si el original no
        // llega, el backend la borra al descartar el archivo.
        if (autorizacion.urlMiniatura && item.miniatura && !item.miniaturaSubida) {
          item.miniaturaSubida = await this.enviarMiniatura(autorizacion.urlMiniatura, item.miniatura);
        }
        if (autorizacion.modo === 'SIMPLE') {
          await this.enviarOriginal(item, autorizacion);
        } else {
          item.multipart = {
            archivoId: autorizacion.archivoId,
            numeroPartes: autorizacion.numeroPartes,
            tamanoParte: autorizacion.tamanoParte,
            etags: new Map(),
          };
        }
      }
      if (item.multipart !== null) {
        item.partes = await this.enviarPartes(item, item.multipart);
      }
      item.originalSubido = true;
    }
    await this.completar(item);
  }

  /**
   * Genera la miniatura (antes de autorizar, para conocer su tamaño) y autoriza el archivo. El
   * servidor decide el modo: simple, o multipart para videos de 100 MB o más (T054).
   */
  private async autorizar(item: ItemSubida): Promise<Autorizacion> {
    const clasificacion = item.clasificacion;
    if (!clasificacion.ok) throw new ErrorDeItem('RECHAZADO', clasificacion.mensaje);
    if (!item.miniaturaGenerada) {
      item.miniaturaGenerada = true;
      item.miniatura = await this.generarMiniatura(item.archivo, clasificacion.formato);
      if (item.miniatura) item.vistaPrevia.set(URL.createObjectURL(item.miniatura));
    }
    const miniatura = item.miniatura;
    const peticion: PeticionAutorizar = {
      nombreInvitado: item.nombreInvitado,
      nombreArchivo: item.archivo.name,
      tipoContenido: clasificacion.tipoContenido,
      tamano: item.archivo.size,
      conMiniatura: miniatura !== null,
      ...(miniatura !== null ? { tamanoMiniatura: miniatura.size } : {}),
    };
    const respuesta = await this.sesion.conSesion(item.nombreInvitado, () =>
      this.api.autorizar(peticion),
    );
    item.archivoId = respuesta.archivoId;
    // Archivo nuevo para el servidor: su miniatura todavía no está subida.
    item.miniaturaSubida = false;
    return respuesta;
  }

  /** Miniatura de hasta 1 MiB, o `null`: nunca bloquea ni hace fallar la subida (FR-026a). */
  private async generarMiniatura(archivo: File, formato: Formato): Promise<Blob | null> {
    try {
      const miniatura = await Promise.race([
        this.miniaturizador(archivo, formato),
        esperar(TIEMPO_MINIATURA_MS).then(() => null),
      ]);
      return miniatura !== null && miniatura.size >= 1 && miniatura.size <= MAX_MINIATURA_BYTES
        ? miniatura
        : null;
    } catch {
      return null;
    }
  }

  /**
   * Nueva URL para un archivo ya autorizado (reintento manual). Si el servidor ya no lo conoce
   * (`NO_ENCONTRADO`, por ejemplo tras renovar la sesión), devuelve `null` para autorizar de nuevo.
   */
  private async pedirUrlNueva(item: ItemSubida): Promise<AutorizacionSimple | null> {
    const archivoId = item.archivoId;
    if (archivoId === null) return null;
    try {
      return await this.sesion.conSesion(item.nombreInvitado, () => this.api.nuevaUrl(archivoId));
    } catch (error) {
      if (error instanceof ApiError && error.codigo === 'NO_ENCONTRADO') {
        item.archivoId = null;
        return null;
      }
      throw error;
    }
  }

  /**
   * `PUT` del archivo con la URL recién pedida. Un 403 puede ser URL vencida o tamaño distinto del
   * firmado (research R2): se pide **una sola** URL nueva por archivo; si vuelve a dar 403, el
   * archivo queda como fallido en vez de repetir en bucle.
   */
  private async enviarOriginal(item: ItemSubida, autorizacion: AutorizacionSimple): Promise<void> {
    let actual = autorizacion;
    let usoUrlNueva = false;
    for (;;) {
      item.progreso.set(0);
      const estado = await enviarPut(
        actual.url,
        actual.encabezados ?? {},
        item.archivo,
        // Hasta que S3 confirme, el avance no pasa de 99 %.
        (porcentaje) => item.progreso.set(Math.min(porcentaje, 99)),
      );
      if (estado >= 200 && estado < 300) return;
      if (estado === 403 && !usoUrlNueva) {
        usoUrlNueva = true;
        const nueva = await this.pedirUrlNueva(item);
        if (nueva === null) throw new ErrorDeItem('FALLO', MENSAJE_PUT_FALLIDO);
        actual = nueva;
        continue;
      }
      throw new ErrorDeItem('FALLO', estado === 0 ? MENSAJE_SIN_CONEXION : MENSAJE_PUT_FALLIDO);
    }
  }

  /**
   * Sube las partes que faltan de un video (`multipart.ts`, T054). Las URLs de cada parte se piden
   * justo antes de enviarla; una falla deja guardadas las partes ya subidas para el reintento.
   */
  private async enviarPartes(item: ItemSubida, estado: EstadoMultipart): Promise<ParteCompletada[]> {
    try {
      return await subirPartes(
        item.archivo,
        estado,
        {
          pedirUrls: (numeros) =>
            this.sesion.conSesion(item.nombreInvitado, () =>
              this.api.partes(estado.archivoId, numeros),
            ),
          enviarParte: enviarParteXhr,
          esperar,
        },
        // Hasta que el servidor confirme, el avance no pasa de 99 %.
        (porcentaje) => item.progreso.set(Math.min(porcentaje, 99)),
      );
    } catch (error) {
      if (!(error instanceof ErrorMultipart)) throw error;
      if (error.errorApi) {
        if (error.errorApi.codigo === 'NO_ENCONTRADO') {
          // El servidor ya no conoce este archivo (sesión renovada o descartado): un reintento
          // manual lo autoriza de nuevo desde cero.
          item.multipart = null;
          item.archivoId = null;
        }
        throw error.errorApi;
      }
      throw new ErrorDeItem(
        'FALLO',
        error.causa === 'RED' ? MENSAJE_SIN_CONEXION : MENSAJE_PUT_FALLIDO,
      );
    }
  }

  /** La miniatura es opcional: cualquier falla solo la descarta (FR-026a). */
  private async enviarMiniatura(url: string, miniatura: Blob): Promise<boolean> {
    const estado = await enviarPut(url, { 'Content-Type': 'image/jpeg' }, miniatura);
    return estado >= 200 && estado < 300;
  }

  /** `completar` con reintentos (espera creciente) ante red caída, error del servidor o `SUBIDA_INCOMPLETA`. */
  private async completar(item: ItemSubida): Promise<void> {
    const archivoId = item.archivoId;
    if (archivoId === null) throw new ErrorDeItem('FALLO', MENSAJE_PUT_FALLIDO);
    for (let intento = 0; ; intento++) {
      try {
        await this.sesion.conSesion(item.nombreInvitado, () =>
          this.api.completar(archivoId, {
            ...(item.partes !== null ? { partes: item.partes } : {}),
            miniaturaSubida: item.miniaturaSubida,
          }),
        );
        return;
      } catch (error) {
        if (error instanceof ApiError && error.codigo === 'SUBIDA_INCOMPLETA') {
          // El objeto (o alguna parte) no está en S3: un reintento manual tiene que enviarlo otra
          // vez. En multipart no se sabe qué parte falló, así que se reenvían todas.
          item.originalSubido = false;
          if (item.multipart !== null) {
            // Sin partes no se puede volver a completar: reintentar aquí daría 400. Queda como
            // fallido y "Reintentar" vuelve a subir las partes.
            item.multipart.etags.clear();
            item.partes = null;
            throw error;
          }
        } else if (error instanceof ApiError && error.codigo === 'NO_ENCONTRADO') {
          // El servidor ya no conoce este archivo: un reintento manual tiene que autorizarlo de nuevo.
          item.originalSubido = false;
          item.archivoId = null;
          item.multipart = null;
          item.partes = null;
        }
        if (!esReintentableAlCompletar(error) || intento >= ESPERAS_COMPLETAR_MS.length) {
          throw error;
        }
        await esperar(ESPERAS_COMPLETAR_MS[intento]);
      }
    }
  }
}
