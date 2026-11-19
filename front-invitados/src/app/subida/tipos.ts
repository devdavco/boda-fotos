/**
 * Formatos y límites de la subida (FR-010, FR-011, data-model.md §2), espejo de
 * `FormatoArchivo.java`. Esta validación es solo una ayuda para no gastar red ni tope de sesión: el
 * servidor decide y repite todas las comprobaciones.
 */

export type TipoMedio = 'FOTO' | 'VIDEO';

export interface Formato {
  /** Valor de `formato` en el API: jpg, png, heic, webp, mp4, mov. */
  clave: string;
  /** `Content-Type` canónico del formato (el que firma el servidor). */
  contentType: string;
  tipo: TipoMedio;
  /** Tipos que el navegador puede informar para este formato. */
  tiposAceptados: string[];
}

const JPG: Formato = {
  clave: 'jpg',
  contentType: 'image/jpeg',
  tipo: 'FOTO',
  tiposAceptados: ['image/jpeg', 'image/jpg'],
};
const PNG: Formato = {
  clave: 'png',
  contentType: 'image/png',
  tipo: 'FOTO',
  tiposAceptados: ['image/png'],
};
const HEIC: Formato = {
  clave: 'heic',
  contentType: 'image/heic',
  tipo: 'FOTO',
  tiposAceptados: ['image/heic', 'image/heif'],
};
const WEBP: Formato = {
  clave: 'webp',
  contentType: 'image/webp',
  tipo: 'FOTO',
  tiposAceptados: ['image/webp'],
};
const MP4: Formato = {
  clave: 'mp4',
  contentType: 'video/mp4',
  tipo: 'VIDEO',
  tiposAceptados: ['video/mp4'],
};
const MOV: Formato = {
  clave: 'mov',
  contentType: 'video/quicktime',
  tipo: 'VIDEO',
  tiposAceptados: ['video/quicktime'],
};

const FORMATO_POR_EXTENSION: Readonly<Record<string, Formato>> = {
  jpg: JPG,
  jpeg: JPG,
  png: PNG,
  heic: HEIC,
  webp: WEBP,
  mp4: MP4,
  mov: MOV,
};

/** Foto máxima: 40 MB. */
export const MAX_FOTO_BYTES = 41_943_040;
/** Video máximo: 500 MB. */
export const MAX_VIDEO_BYTES = 524_288_000;
/** Desde este tamaño un video se sube en multipart (T054): 100 MB. */
export const UMBRAL_MULTIPART_BYTES = 104_857_600;

/** Mensajes idénticos a los de `contracts/api.md` (sección "Errores"). */
export const MENSAJES = {
  TIPO_NO_PERMITIDO:
    'Este tipo de archivo no se puede subir. Usa fotos JPG, PNG, HEIC o WEBP, o videos MP4 o MOV.',
  ARCHIVO_VACIO: 'El archivo está vacío. Elige otro.',
  FOTO_MUY_GRANDE: 'La foto supera el máximo de 40 MB.',
  VIDEO_MUY_GRANDE: 'El video supera el máximo de 500 MB. Elige uno más corto.',
} as const;

export interface DatosArchivo {
  name: string;
  type: string;
  size: number;
}

export type Clasificacion =
  | {
      ok: true;
      formato: Formato;
      tipo: TipoMedio;
      /** Tipo para el campo opcional `tipoContenido` del API; nunca contradice la extensión. */
      tipoContenido: string;
    }
  | {
      ok: false;
      codigo: 'TIPO_NO_PERMITIDO' | 'ARCHIVO_VACIO' | 'ARCHIVO_MUY_GRANDE';
      mensaje: string;
    };

/** Extensión en minúsculas, sin punto; cadena vacía si el nombre no tiene. */
export function extensionDe(nombre: string): string {
  const punto = nombre.lastIndexOf('.');
  if (punto < 0 || punto === nombre.length - 1) return '';
  return nombre
    .slice(punto + 1)
    .trim()
    .toLowerCase();
}

/** Formato según la extensión, como el servidor; `null` si no está permitido. */
export function formatoPorNombre(nombre: string): Formato | null {
  return FORMATO_POR_EXTENSION[extensionDe(nombre)] ?? null;
}

/**
 * Tipo de contenido que se informa al API. Android entrega `file.type` vacío para HEIC y MOV, así
 * que el tipo sale de la extensión. Si el navegador informa un tipo y es uno de los del formato, se
 * usa ese; si informa otro, se usa el de la extensión (el navegador se equivoca a veces) y la firma
 * de los primeros bytes que el servidor revisa al completar decide si el contenido es válido.
 */
export function tipoContenidoDe(formato: Formato, tipoDelNavegador: string): string {
  const tipo = tipoDelNavegador.split(';', 1)[0].trim().toLowerCase();
  return formato.tiposAceptados.includes(tipo) ? tipo : formato.contentType;
}

/** Valida un archivo elegido, en el mismo orden que el servidor: formato, tamaño 0, tamaño máximo. */
export function clasificar(archivo: DatosArchivo): Clasificacion {
  const formato = formatoPorNombre(archivo.name);
  if (formato === null) {
    return { ok: false, codigo: 'TIPO_NO_PERMITIDO', mensaje: MENSAJES.TIPO_NO_PERMITIDO };
  }
  if (archivo.size <= 0) {
    return { ok: false, codigo: 'ARCHIVO_VACIO', mensaje: MENSAJES.ARCHIVO_VACIO };
  }
  if (formato.tipo === 'FOTO' && archivo.size > MAX_FOTO_BYTES) {
    return { ok: false, codigo: 'ARCHIVO_MUY_GRANDE', mensaje: MENSAJES.FOTO_MUY_GRANDE };
  }
  if (formato.tipo === 'VIDEO' && archivo.size > MAX_VIDEO_BYTES) {
    return { ok: false, codigo: 'ARCHIVO_MUY_GRANDE', mensaje: MENSAJES.VIDEO_MUY_GRANDE };
  }
  return {
    ok: true,
    formato,
    tipo: formato.tipo,
    tipoContenido: tipoContenidoDe(formato, archivo.type),
  };
}
