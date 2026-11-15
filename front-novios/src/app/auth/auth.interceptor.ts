import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { aApiError } from '../api/api';
import { GaleriaEstado } from '../galeria/galeria-estado';
import { Auth } from './auth.service';

const PREFIJO_ADMIN = '/api/admin/';
const RUTA_LOGIN = '/api/admin/login';

/**
 * Agrega `Authorization: Bearer <token>` **solo** a `/api/admin/*` (nunca a las URLs de S3, que
 * ya van firmadas y rechazarían un encabezado de más) y convierte los errores `{codigo, mensaje}`
 * en `ApiError`. Un 401 fuera del login (token vencido por 8 h de inactividad o sesión cerrada)
 * borra el token y vuelve a `/entrar` (FR-030).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) return next(req);
  const auth = inject(Auth);
  const router = inject(Router);
  const galeria = inject(GaleriaEstado);
  const esAdmin = req.url.startsWith(PREFIJO_ADMIN) && req.url !== RUTA_LOGIN;
  const token = esAdmin ? auth.token() : null;
  const peticion = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(peticion).pipe(
    catchError((error) => {
      const apiError = aApiError(error);
      if (esAdmin && apiError.estado === 401) {
        auth.olvidar();
        galeria.reiniciar(); // la próxima sesión no ve la lista ni el cursor de esta
        void router.navigateByUrl('/entrar');
      }
      return throwError(() => apiError);
    }),
  );
};
