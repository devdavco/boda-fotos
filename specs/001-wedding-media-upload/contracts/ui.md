# Contrato de interfaz (front-ends)

**Feature**: `001-wedding-media-upload` | **Fecha**: 2026-11-14 | **Plan**: [../plan.md](../plan.md)

Lo que ve cada usuario y qué llamada del [API](api.md) lo respalda. Todo el texto está en español
(Principio VI). Los mensajes de error vienen del campo `mensaje` del API.

## Front de invitados (`front-invitados/`, Amplify)

- **URL del QR**: la raíz del dominio de Amplify de la app de invitados
  (`https://david-release.<appId>.amplifyapp.com/`; el subdominio sale del nombre de la rama
  `david/release`, por eso no se renombra). Es una sola pantalla, sin rutas internas (FR-001).
- **Al abrir**: `GET /api/recepcion/estado`.

| Estado | Lo que se muestra |
| --- | --- |
| `NO_INICIADA` | "La recepción de fotos y videos abre el jueves 19 de noviembre de 2026." Sin selector de archivos. |
| `CERRADA` | "La recepción de fotos y videos ya cerró. ¡Gracias por compartir!" Sin selector. |
| `ABIERTA` | Flujo de subida. |

Flujo de subida (FR-003 a FR-008, SC-001):

1. **Aviso de uso de las fotos**, siempre visible arriba del formulario (FR-003):
   "Las fotos y videos que subas solo los verán Mara y Daniel en su galería privada. No se
   publican ni se comparten."
2. **Nombre**, prellenado desde `localStorage` si existe (FR-004a). Validación local de 1 a 60
   caracteres. Si no hay `sesionId` guardado, se llama a `POST /api/sesiones-subida` al tocar
   "Elegir fotos y videos".
3. **Botón "Elegir fotos y videos"** con el `<input type="file" multiple>` de research R13.
4. **Lista de archivos**, uno por fila: miniatura local o ícono, nombre, tamaño, barra de progreso
   y estado (`En cola`, `Subiendo 42 %`, `Listo ✓`, `Falló: <mensaje>`, `Rechazado: <mensaje>`).
   Los rechazos locales (tipo o tamaño) se muestran sin llamar al API y no frenan a los demás.
5. **"Reintentar fallidos"** aparece si hay fallidos; reenvía solo esos (FR-007). Los
   rechazados por regla (tipo, tamaño, tope, recepción cerrada) no se reintentan.
6. Mientras hay subidas activas: "Mantén esta pantalla abierta hasta que terminen las subidas."
7. Al terminar todo: "¡Listo! Subiste N archivos. Puedes elegir más cuando quieras."

`localStorage`: `boda.sesionId`, `boda.nombre`. Si el API responde `SESION_INVALIDA`, se borran y
se crea una sesión nueva con el nombre actual.

## Front de novios (`front-novios/`, Amplify)

Rutas:

| Ruta | Pantalla | API |
| --- | --- | --- |
| `/entrar` | Login: usuario, contraseña, botón "Entrar" | `POST /api/admin/login` |
| `/` | Galería (requiere sesión; si no, redirige a `/entrar`) | `GET /api/admin/resumen`, `GET /api/admin/galeria` |
| `/archivo/:id` | Vista completa con "Descargar" y "Eliminar" | `GET /api/admin/archivos/{id}/url` |

Galería (FR-026, FR-030b, SC-007):
- **Cabecera**: "1.240 archivos (1.102 fotos, 138 videos) · Última subida: sábado 21, 9:41 p. m."
  y el estado de la recepción con el botón **"Cerrar la recepción ahora"**. El botón pide
  confirmación: "¿Cerrar la recepción? Los invitados ya no podrán subir más fotos ni videos.
  Esto no se puede deshacer." (FR-029).
- **Banner fijo**: "Puedes ver y descargar el material hasta el martes 1 de diciembre de 2026 a
  las 11:59 p. m. Después, solo se podrá recuperar pidiéndoselo al responsable técnico."
- **Cuadrícula**: 3 columnas en celular, más en pantallas grandes. Cada celda muestra la miniatura
  (o el ícono de foto HEIC / video), el nombre del invitado y la fecha y hora de Cali. Carga 24
  más al acercarse al final (scroll infinito con `IntersectionObserver`). Sin filtros ni búsqueda
  (FR-034).
- **Vista completa**: `<img>` para JPG, PNG y WEBP; `<video controls playsinline>` para MP4 y
  MOV; HEIC muestra el ícono y "Este formato no se puede previsualizar aquí; descárgalo para
  verlo." Botón "Descargar original" (`uso=descargar`).
- **Eliminar**: confirmación "¿Eliminar este archivo? No se puede recuperar." (FR-028). Si otro
  novio ya lo eliminó, aparece el mensaje de `NO_ENCONTRADO` y se vuelve a la galería.
- **"Cerrar sesión"** en la cabecera (`POST /api/admin/logout`, borra el token).
- **Después del fin de disponibilidad**, o si el API responde `PERIODO_TERMINADO`: en vez de la
  galería, "El periodo para ver y descargar terminó el 1 de diciembre de 2026. Para recuperar el
  material, escribe al responsable técnico." El front también muestra este mensaje, según la
  fecha y la hora, si el backend ya está apagado (después del 2026-12-02).

Token en `sessionStorage` (`boda.token`). Un interceptor de `HttpClient` agrega
`Authorization: Bearer` **solo a `/api/admin/*`**, nunca a las URLs de S3. Si llega un `401`, se
borra el token y se vuelve a `/entrar`.
