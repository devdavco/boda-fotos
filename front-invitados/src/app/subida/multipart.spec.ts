import { ApiError, UrlsPartes } from '../api/api';
import {
  DependenciasMultipart,
  ErrorMultipart,
  EstadoMultipart,
  RespuestaParte,
  TIEMPO_MAXIMO_SIN_RED_MS,
  rangoParte,
  subirPartes,
} from './multipart';

const MiB = 1024 * 1024;

/** Archivo de `partes` partes de 1 MiB (la última de medio MiB). */
function archivoDe(partes: number): Blob {
  return new Blob([new Uint8Array(partes * MiB - MiB / 2)]);
}

function estadoDe(partes: number, etags: [number, string][] = []): EstadoMultipart {
  return { archivoId: 'a', numeroPartes: partes, tamanoParte: MiB, etags: new Map(etags) };
}

/**
 * Dependencias falsas: `respuestas[n]` es la lista de respuestas del PUT de la parte `n`, en orden;
 * cuando se acaba, responde 200 con ETag "e<n>". Registra las peticiones y el reloj simulado.
 */
function falsas(respuestas: Record<number, RespuestaParte[]> = {}) {
  const urlsPedidas: number[][] = [];
  const putsPorParte = new Map<number, number>();
  const esperas: number[] = [];
  let enParalelo = 0;
  let maxParalelo = 0;
  let reloj = 0;
  const deps: DependenciasMultipart = {
    async pedirUrls(numeros): Promise<UrlsPartes> {
      urlsPedidas.push(numeros);
      return { urls: numeros.map((n) => ({ numero: n, url: `u${n}` })), expiraEn: '' };
    },
    async enviarParte(url, cuerpo, alAvance): Promise<RespuestaParte> {
      const n = Number(url.slice(1));
      putsPorParte.set(n, (putsPorParte.get(n) ?? 0) + 1);
      enParalelo++;
      maxParalelo = Math.max(maxParalelo, enParalelo);
      await Promise.resolve();
      alAvance(cuerpo.size);
      enParalelo--;
      return respuestas[n]?.shift() ?? { estado: 200, etag: `"e${n}"` };
    },
    async esperar(ms) {
      esperas.push(ms);
      reloj += ms;
      vi.setSystemTime(reloj);
    },
  };
  return { deps, urlsPedidas, putsPorParte, esperas, max: () => maxParalelo };
}

describe('multipart', () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ['Date'] }).setSystemTime(0));
  afterEach(() => vi.useRealTimers());

  it('calcula el rango de cada parte, con el resto en la última', () => {
    expect(rangoParte(25, 10, 1)).toEqual([0, 10]);
    expect(rangoParte(25, 10, 3)).toEqual([20, 25]);
  });

  it('sube todas las partes, una URL por parte justo antes de enviarla, máximo 3 a la vez', async () => {
    const f = falsas();
    const progreso: number[] = [];
    const partes = await subirPartes(archivoDe(7), estadoDe(7), f.deps, (p) => progreso.push(p));
    expect(partes.map((p) => p.numero)).toEqual([1, 2, 3, 4, 5, 6, 7]);
    expect(partes[0].etag).toBe('"e1"');
    expect(f.urlsPedidas.every((n) => n.length === 1)).toBe(true);
    expect(f.max()).toBeLessThanOrEqual(3);
    expect(progreso.at(-1)).toBe(100);
  });

  it('al reintentar no reenvía las partes ya subidas y el progreso arranca donde iba', async () => {
    const f = falsas();
    const progreso: number[] = [];
    await subirPartes(archivoDe(4), estadoDe(4, [[1, '"x1"'], [2, '"x2"']]), f.deps, (p) =>
      progreso.push(p),
    );
    expect([...f.putsPorParte.keys()].sort()).toEqual([3, 4]);
    expect(progreso[0]).toBeGreaterThan(50);
  });

  it('un 403 (URL vencida) pide otra URL y reintenta sin contarlo como fallo', async () => {
    const f = falsas({ 2: [{ estado: 403, etag: null }] });
    const partes = await subirPartes(archivoDe(3), estadoDe(3), f.deps, () => {});
    expect(partes).toHaveLength(3);
    expect(f.putsPorParte.get(2)).toBe(2);
    expect(f.urlsPedidas.filter((n) => n[0] === 2)).toHaveLength(2);
    expect(f.esperas).toHaveLength(0);
  });

  it('dos 403 seguidos en la misma parte fallan (tamaño distinto del firmado), sin bucle', async () => {
    const f = falsas({ 1: [{ estado: 403, etag: null }, { estado: 403, etag: null }] });
    const estado = estadoDe(3);
    await expect(subirPartes(archivoDe(3), estado, f.deps, () => {})).rejects.toMatchObject({
      causa: 'TAMANO',
    });
    expect(f.putsPorParte.get(1)).toBe(2);
  });

  it('sin red espera con espera creciente y sigue cuando vuelve la conexión (corte > 5 min)', async () => {
    const sinRed = Array.from({ length: 15 }, () => ({ estado: 0, etag: null }));
    const f = falsas({ 1: sinRed });
    const partes = await subirPartes(archivoDe(2), estadoDe(2), f.deps, () => {});
    expect(partes).toHaveLength(2);
    expect(f.esperas.slice(0, 6)).toEqual([1000, 2000, 4000, 8000, 16000, 30000]);
    expect(f.esperas.reduce((a, b) => a + b, 0)).toBeGreaterThan(5 * 60_000);
  });

  it('se rinde tras 15 minutos sin red y conserva las partes que sí subieron', async () => {
    const f = falsas({ 2: Array.from({ length: 100 }, () => ({ estado: 0, etag: null })) });
    const estado = estadoDe(3);
    const promesa = subirPartes(archivoDe(3), estado, f.deps, () => {});
    await expect(promesa).rejects.toBeInstanceOf(ErrorMultipart);
    await expect(promesa).rejects.toMatchObject({ causa: 'RED' });
    expect(f.esperas.reduce((a, b) => a + b, 0)).toBeGreaterThanOrEqual(TIEMPO_MAXIMO_SIN_RED_MS);
    expect(estado.etags.has(1)).toBe(true);
    expect(estado.etags.has(2)).toBe(false);
  });

  it('un error del API que es regla (recepción cerrada) se propaga sin reintentar', async () => {
    const f = falsas();
    const cerrada = new ApiError('RECEPCION_CERRADA', 'La recepción de fotos y videos ya cerró.', 403);
    f.deps.pedirUrls = async () => {
      throw cerrada;
    };
    await expect(subirPartes(archivoDe(2), estadoDe(2), f.deps, () => {})).rejects.toMatchObject({
      causa: 'API',
      errorApi: cerrada,
    });
    expect(f.esperas).toHaveLength(0);
  });

  it('sin ETag en la respuesta (CORS mal configurado) falla en vez de completar mal', async () => {
    const f = falsas({ 1: [{ estado: 200, etag: null }] });
    await expect(subirPartes(archivoDe(1), estadoDe(1), f.deps, () => {})).rejects.toMatchObject({
      causa: 'S3',
    });
  });
});
