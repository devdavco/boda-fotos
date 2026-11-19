import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { aApiError } from '../api/api';
import { Auth } from './auth.service';

/**
 * Login de los novios (contracts/ui.md, `/entrar`). Muestra el `mensaje` del API tal cual: el
 * mismo para usuario inexistente, contraseña mala o cuenta bloqueada (FR-025).
 */
@Component({
  selector: 'app-entrar',
  template: `
    <main class="entrar">
      <h1>Fotos y videos de la boda</h1>
      <form (submit)="enviar($event)" novalidate>
        <div class="campo">
          <label for="usuario">Usuario</label>
          <input
            id="usuario"
            name="usuario"
            autocomplete="username"
            autocapitalize="none"
            spellcheck="false"
            required
            [value]="usuario()"
            (input)="usuario.set(valor($event))"
          />
        </div>
        <div class="campo">
          <label for="contrasena">Contraseña</label>
          <input
            id="contrasena"
            name="contrasena"
            type="password"
            autocomplete="current-password"
            required
            [value]="contrasena()"
            (input)="contrasena.set(valor($event))"
          />
        </div>
        @if (error(); as mensaje) {
          <p class="error" role="alert">{{ mensaje }}</p>
        }
        <button type="submit" class="primario" [disabled]="enviando()">
          {{ enviando() ? 'Entrando…' : 'Entrar' }}
        </button>
      </form>
    </main>
  `,
  styles: `
    .entrar {
      max-width: 22rem;
    }
    form {
      display: grid;
      gap: 1rem;
    }
  `,
})
export class Entrar {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);

  protected readonly usuario = signal('');
  protected readonly contrasena = signal('');
  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);

  protected valor(evento: Event): string {
    return (evento.target as HTMLInputElement).value;
  }

  protected async enviar(evento: Event): Promise<void> {
    evento.preventDefault();
    if (this.enviando()) return;
    if (this.usuario().trim() === '' || this.contrasena() === '') {
      this.error.set('Escribe tu usuario y tu contraseña.');
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    try {
      await this.auth.entrar(this.usuario(), this.contrasena());
      this.contrasena.set('');
      await this.router.navigateByUrl('/');
    } catch (e) {
      this.error.set(aApiError(e).mensaje);
    } finally {
      this.enviando.set(false);
    }
  }
}
