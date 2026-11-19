import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Front de los novios (FR-023): login, galería y vista completa (contracts/ui.md). */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
export class App {}
