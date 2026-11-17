import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth.service';

/** Sin token, las pantallas de la galería redirigen a `/entrar` (contracts/ui.md). */
export const authGuard: CanActivateFn = () => {
  return inject(Auth).haySesion() ? true : inject(Router).parseUrl('/entrar');
};

/** Con sesión, `/entrar` lleva directo a la galería. */
export const invitadoGuard: CanActivateFn = () => {
  return inject(Auth).haySesion() ? inject(Router).parseUrl('/') : true;
};
