import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { apiInterceptor } from './api/api';
import { recepcionInterceptor } from './estado/estado';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([apiInterceptor, recepcionInterceptor])),
  ],
};
