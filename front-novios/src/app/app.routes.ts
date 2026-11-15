import { Routes } from '@angular/router';
import { authGuard, invitadoGuard } from './auth/auth.guard';
import { Entrar } from './auth/entrar';

/** Rutas de contracts/ui.md. La galería y la vista completa exigen sesión. */
export const routes: Routes = [
  { path: 'entrar', component: Entrar, canActivate: [invitadoGuard], title: 'Entrar' },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./galeria/galeria').then((m) => m.Galeria),
    title: 'Fotos y videos de la boda',
  },
  {
    path: 'archivo/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./archivo/archivo').then((m) => m.Archivo),
    title: 'Archivo',
  },
  { path: '**', redirectTo: '' },
];
