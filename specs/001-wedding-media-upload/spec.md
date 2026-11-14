# Feature Specification: Fotos y videos de la boda vía QR

**Feature Branch**: `001-wedding-media-upload`

**Created**: 2026-11-13

**Status**: Draft

**Input**: User description: "reqs/requerimientos_fotos_boda.md" — los invitados de la boda de Mara y
Daniel (21 de noviembre de 2026, Cali) escanean un QR, indican su nombre y suben fotos y videos desde
el celular a un almacenamiento privado; los novios consultan, descargan y administran el material
desde un front-end privado y separado.

## Clarifications

### Session 2026-11-13

- Q: ¿Cómo deben verse en la galería las fotos HEIC y los videos? → A: Opción C — miniatura real
  para JPG/PNG/WEBP; para videos, el celular del invitado genera una miniatura (un fotograma) antes
  de subir, sin procesamiento en el servidor; HEIC (y videos sin miniatura) se muestran con ícono de
  tipo y siempre se pueden descargar.
- Q: ¿Debe haber un límite de cuánto puede subir una sola persona? → A: Opción B — límite por
  sesión de subida de 300 archivos o 5 GB (lo que ocurra primero), aplicado por el servidor.
- Q: ¿Qué cuenta como una sesión de subida? → A: Opción B — la sesión se recuerda en el navegador
  del celular hasta el cierre de la recepción; el nombre aparece prellenado y puede cambiarse, y el
  tope es acumulado para esa sesión.
- Q: ¿Cómo se detecta que el sistema dejó de recibir fotos durante la boda? → A: Opción C — alerta
  automática por correo a un responsable técnico si el servicio no responde o aumentan los errores
  al autorizar subidas, más un contador de archivos recibidos visible en la galería de los novios.
- Q: ¿Los novios necesitan filtrar o buscar en la galería? → A: Opción A — sin filtros ni búsqueda;
  cuadrícula ordenada del más reciente al más antiguo con carga progresiva.
- Q: ¿Se borra automáticamente el material después del 1 de diciembre? → A: No. Daniel decidió
  quitar el borrado automático del 2 de diciembre; originales y miniaturas se conservan en el
  almacenamiento privado hasta nuevo aviso de los novios. La regla de lifecycle de expiración ya
  se eliminó del bucket y solo queda la que aborta cargas multipart incompletas. La galería sigue
  dejando de mostrar el material después del 1 de diciembre a las 23:59 (FR-032 no cambia); la
  eliminación definitiva será manual, por el responsable técnico, cuando los novios la pidan.

### Session 2026-11-14

- Q: ¿Cómo se muestran las fotos JPG, PNG y WEBP en la cuadrícula sin cargar el original? → A: El
  celular del invitado genera también una miniatura para JPG, PNG y WEBP, con el mismo mecanismo
  que para los videos y sin procesamiento en el servidor; HEIC sigue mostrándose con ícono. Motivo:
  con originales de 3–8 MB como miniatura no se cumple SC-007. Si la miniatura de una foto no se
  genera, la galería muestra el original con carga diferida.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Invitado sube fotos y videos desde el QR (Priority: P1)

Un invitado escanea el QR del evento con su celular, llega a una página en español pensada para
móvil, lee el aviso de uso de las fotos, escribe su nombre, selecciona (o captura) una o varias
fotos y videos y los sube. Ve el progreso de cada archivo, cuáles terminaron bien y puede reintentar
solo los que fallaron.

**Why this priority**: es la razón de ser del sistema. Si los invitados no pueden subir durante el
evento, el material se pierde; todo lo demás puede resolverse después.

**Independent Test**: con la recepción abierta, desde un iPhone (Safari) y un Android (Chrome) en red
móvil, escanear el QR, subir varias fotos y un video grande, y confirmar que todos quedan
almacenados de forma privada con el nombre del invitado asociado.

**Acceptance Scenarios**:

1. **Given** la recepción está abierta, **When** el invitado escanea el QR, **Then** la página de
   subida se abre directamente en el navegador del celular sin instalar nada ni crear cuenta.
2. **Given** el invitado no ha escrito su nombre, **When** intenta subir, **Then** el sistema le pide
   el nombre antes de continuar.
3. **Given** el invitado seleccionó 5 fotos y 1 video permitidos, **When** inicia la subida, **Then**
   ve el progreso individual de cada archivo y una confirmación por cada archivo completado.
4. **Given** 2 de 6 archivos fallaron por pérdida de señal, **When** el invitado pulsa reintentar,
   **Then** solo se vuelven a enviar los 2 fallidos y los 4 completados no se repiten.
5. **Given** un video de 300 MB cuya transferencia se interrumpe a mitad, **When** se reintenta,
   **Then** la subida continúa sin empezar desde cero.
6. **Given** el invitado selecciona un archivo de tipo no permitido o que excede el límite, **When**
   intenta subirlo, **Then** el sistema lo rechaza con un mensaje claro en español indicando el
   motivo, y los demás archivos válidos siguen su curso.
7. **Given** dos invitados suben archivos con el mismo nombre original, **When** ambas subidas
   terminan, **Then** ambos archivos se conservan sin que uno sobrescriba al otro.

---

### User Story 2 - La recepción respeta su ventana de tiempo (Priority: P1)

El sistema acepta archivos solo desde el jueves 19 de noviembre de 2026 hasta el domingo 22 de
noviembre de 2026 a las 12:00 p. m., hora de Cali. Fuera de ese periodo, la página informa que la
recepción está cerrada y el sistema no autoriza nuevas subidas, aunque alguien intente saltarse la
página.

**Why this priority**: es una regla de negocio explícita de los novios y una protección de costos y
privacidad; sin ella el sistema queda abierto indefinidamente.

**Independent Test**: configurar la ventana en un entorno de prueba con horas cercanas, verificar el
mensaje de "recepción cerrada" antes de la apertura y después del cierre, y comprobar que las
solicitudes directas de subida también son rechazadas.

**Acceptance Scenarios**:

1. **Given** es antes de la apertura, **When** un invitado abre la página, **Then** ve un mensaje de
   que la recepción aún no ha comenzado, con la fecha de apertura, y no puede subir.
2. **Given** es después del cierre, **When** un invitado abre la página o intenta subir, **Then** ve
   que la recepción está cerrada y el sistema no autoriza nuevas subidas.
3. **Given** una subida grande en partes comenzó antes del cierre, **When** se intenta finalizar
   después del cierre, **Then** el sistema rechaza la finalización y el archivo no queda en la
   galería.
4. **Given** alguien intenta solicitar autorización de subida directamente (sin usar la página)
   fuera de la ventana, **When** el sistema recibe la solicitud, **Then** la rechaza.

---

### User Story 3 - Novios consultan y descargan el material (Priority: P2)

Los novios entran a su front-end privado (distinto al de invitados) con usuario y contraseña, ven una
cuadrícula con miniaturas de todas las fotos y videos recibidos, con la fecha de subida y el nombre
declarado por el invitado, abren cualquiera en tamaño completo y lo descargan individualmente.

**Why this priority**: es el objetivo final, pero puede estar listo unos días después de abrir la
recepción, porque el material ya queda guardado de forma segura.

**Independent Test**: con archivos ya subidos, iniciar sesión como novio, recorrer la cuadrícula,
abrir una foto y un video en tamaño completo y descargarlos.

**Acceptance Scenarios**:

1. **Given** un novio con credenciales válidas, **When** inicia sesión, **Then** accede a la galería
   del evento.
2. **Given** credenciales inválidas (usuario o contraseña incorrectos), **When** se intenta iniciar
   sesión, **Then** se muestra el mismo mensaje genérico en ambos casos, sin revelar cuál falló.
3. **Given** múltiples intentos fallidos seguidos, **When** se sigue intentando, **Then** el sistema
   bloquea temporalmente nuevos intentos.
4. **Given** la sesión iniciada, **When** el novio ve la galería, **Then** ve miniaturas de fotos y
   videos con fecha de subida y nombre declarado del invitado.
5. **Given** un archivo en la galería, **When** el novio lo abre, **Then** lo ve en tamaño completo
   (o lo reproduce si es video) y puede descargar el original.
6. **Given** alguien sin sesión, **When** intenta abrir la galería o el enlace de un archivo,
   **Then** no puede ver ni descargar el contenido.

---

### User Story 4 - Novios administran archivos y la recepción (Priority: P3)

Desde su front-end privado, los novios pueden eliminar fotos o videos no deseados y cerrar la
recepción antes de la hora prevista.

**Why this priority**: es útil pero no bloquea el evento; un archivo indeseado puede eliminarse
después y el cierre automático ya existe.

**Independent Test**: con sesión iniciada, eliminar un archivo y verificar que desaparece de la
galería; cerrar la recepción y verificar que la página de invitados la muestra cerrada.

**Acceptance Scenarios**:

1. **Given** un archivo en la galería, **When** el novio lo elimina y confirma, **Then** el archivo
   desaparece de la galería y deja de poder descargarse.
2. **Given** la recepción está abierta, **When** el novio la cierra anticipadamente, **Then** a partir
   de ese momento no se autorizan nuevas subidas ni finalizaciones, y la página de invitados muestra
   que está cerrada, también después de un reinicio del sistema.

---

### User Story 5 - El material deja de estar disponible al terminar el periodo (Priority: P3)

Las fotos y videos se pueden consultar y descargar hasta el 1 de diciembre de 2026. Después dejan de
estar disponibles en la galería y se aplica la política de retención definida.

**Why this priority**: necesario para privacidad y costos, pero ocurre días después del evento.

**Independent Test**: simular una fecha posterior al fin del periodo y verificar que la galería ya no
muestra ni permite descargar el material.

**Acceptance Scenarios**:

1. **Given** es el 1 de diciembre de 2026 antes de las 23:59 hora de Cali, **When** los novios
   ingresan, **Then** pueden consultar y descargar el material.
2. **Given** terminó el periodo de disponibilidad, **When** los novios ingresan, **Then** la galería
   informa que el periodo terminó y no permite ver ni descargar archivos.
3. **Given** terminó el periodo de disponibilidad, **When** se revisa el almacenamiento, **Then** los
   originales y miniaturas del evento siguen conservados en el almacenamiento privado (sin borrado
   automático) hasta que los novios pidan su eliminación.

---

### Edge Cases

- El invitado pierde la conexión a mitad de la subida o cierra el navegador: los archivos completados
  se conservan; los incompletos pueden reintentarse si vuelve a seleccionarlos.
- Subidas en partes que nunca se completan: el sistema las limpia automáticamente para no dejar
  almacenamiento residual.
- Archivo con extensión permitida pero tamaño 0 o contenido declarado inconsistente: se rechaza.
- Foto HEIC, o video cuya miniatura no pudo generarse en el celular del invitado: la galería
  muestra un ícono de tipo de archivo en lugar de una miniatura rota y permite descargarlo
  igualmente.
- La generación de la miniatura de un video falla o el navegador no la soporta: el video se sube
  de todas formas, sin miniatura; nunca se bloquea la subida por la miniatura.
- El invitado deja la página abierta desde antes del cierre e intenta subir después: el sistema lo
  rechaza e informa que la recepción cerró.
- Nombre del invitado vacío, solo espacios o excesivamente largo: se pide un nombre válido (1 a 60
  caracteres).
- Muchos invitados suben al mismo tiempo durante la fiesta: el sistema sigue aceptando subidas.
- Un invitado alcanza el tope de su sesión (300 archivos o 5 GB): los archivos ya autorizados
  terminan normalmente; los siguientes se rechazan con un mensaje que explica el límite.
- El invitado borra los datos del navegador, usa modo incógnito o cambia de navegador: se crea una
  sesión nueva y se le pide el nombre otra vez; se acepta.
- Varias personas usan el mismo celular: cambian el nombre antes de subir; los archivos quedan
  asociados al nombre vigente, aunque compartan sesión y tope.
- El nombre original del archivo contiene caracteres especiales o emojis: se conserva como dato
  informativo sin afectar el almacenamiento.
- Un novio elimina un archivo mientras el otro lo está viendo: la descarga posterior falla con un
  mensaje claro.

## Requirements *(mandatory)*

### Functional Requirements

**Acceso y subida (invitados)**

- **FR-001**: El sistema MUST ofrecer una única dirección web del evento que pueda codificarse en un
  QR y abrirse directamente en el navegador del celular, sin instalar nada ni crear cuenta.
- **FR-002**: La página de invitados MUST estar en español, ser responsiva y diseñada primero para
  celular, y funcionar en versiones recientes de Chrome, Safari (iOS) y Samsung Internet.
- **FR-003**: La página MUST mostrar un aviso sobre el uso de las fotos antes de subir.
- **FR-004**: El invitado MUST indicar su nombre (1 a 60 caracteres) antes de subir; el nombre
  vigente al momento de subir se asocia a cada archivo y se trata como dato declarado, no
  verificado.
- **FR-004a**: La sesión de subida MUST recordarse en el navegador del celular hasta el cierre de
  la recepción: al volver a abrir el QR o recargar la página, el nombre aparece prellenado, el
  invitado puede cambiarlo y los topes de FR-012a siguen acumulando sobre la misma sesión. El
  servidor MUST identificar la sesión con un identificador emitido por él (no adivinable).
- **FR-005**: El invitado MUST poder seleccionar varios archivos a la vez desde la galería del
  celular o capturarlos en el momento, según lo permita su navegador y dispositivo.
- **FR-006**: El sistema MUST mostrar el progreso de cada archivo y confirmar individualmente cuáles
  se completaron y cuáles fallaron.
- **FR-007**: El invitado MUST poder reintentar únicamente los archivos fallidos sin volver a enviar
  los completados.
- **FR-008**: Para videos de 100 MB o más, la subida MUST hacerse en partes, de modo que un reintento
  reenvíe solo las partes fallidas.
- **FR-009**: Los archivos MUST transferirse directamente del dispositivo al almacenamiento privado,
  usando autorizaciones temporales emitidas por el sistema, sin pasar por el servidor de la
  aplicación.

**Formatos y límites**

- **FR-010**: El sistema MUST aceptar solo imágenes JPG, JPEG, PNG, HEIC y WEBP, y videos MP4 y MOV.
- **FR-011**: El sistema MUST rechazar fotos de más de 40 MB y videos de más de 500 MB.
- **FR-012**: El sistema MUST validar tipo y tamaño del lado del servidor antes de autorizar cada
  subida; la validación en la página es solo una ayuda para el invitado.
- **FR-012a**: El servidor MUST limitar cada sesión de subida a un máximo de 300 archivos o 5 GB
  en total (lo que ocurra primero), contando los archivos autorizados en esa sesión. Al alcanzar el
  tope, MUST NOT emitir más autorizaciones para esa sesión y la página MUST mostrar un mensaje claro
  en español. Ambos valores MUST ser configurables sin modificar el código.

**Ventana de recepción**

- **FR-013**: El sistema MUST autorizar subidas solo entre el jueves 19 de noviembre de 2026 a las
  00:00 y el domingo 22 de noviembre de 2026 a las 12:00, hora de Cali (`America/Bogota`), evaluado
  por el servidor.
- **FR-014**: Fuera de la ventana, o tras un cierre anticipado, la página MUST informar que la
  recepción está cerrada (o aún no abre, con su fecha de apertura) y el servidor MUST NOT emitir
  nuevas autorizaciones de subida.
- **FR-015**: Las autorizaciones de subida MUST expirar a más tardar en el momento del cierre.
- **FR-016**: El sistema MUST rechazar la finalización de subidas en partes después del cierre; esos
  archivos no aparecen en la galería.
- **FR-017**: Las fechas de apertura y cierre MUST ser configurables sin modificar el código.

**Almacenamiento**

- **FR-018**: Cada archivo MUST guardarse en almacenamiento privado, organizado por evento, bajo un
  identificador generado por el sistema; el nombre original se conserva como dato informativo.
- **FR-019**: El sistema MUST garantizar que ningún archivo sobrescriba a otro existente.
- **FR-020**: Ningún archivo MUST ser accesible públicamente; toda lectura requiere una autorización
  temporal emitida a un novio autenticado.
- **FR-021**: El sistema MUST limpiar automáticamente las subidas en partes que no se completaron.
- **FR-022**: El sistema MUST registrar por cada archivo: nombre declarado del invitado, nombre
  original, tipo, tamaño y fecha/hora de subida.

**Front-end privado de los novios**

- **FR-023**: Los novios MUST tener un front-end separado del de invitados, accesible solo tras
  iniciar sesión con usuario y contraseña.
- **FR-024**: Las contraseñas MUST almacenarse solo como hash con salt único generado con una función
  diseñada para contraseñas; nunca en texto claro ni cifradas de forma reversible.
- **FR-025**: El inicio de sesión MUST responder con un mensaje genérico ante usuario o contraseña
  incorrectos y MUST bloquear temporalmente tras 5 intentos fallidos consecutivos durante 15 minutos.
- **FR-026**: La galería MUST mostrar en cuadrícula miniaturas de las fotos y videos recibidos, con
  fecha de subida y nombre declarado del invitado, ordenados del más reciente al más antiguo.
  Las fotos JPG, PNG y WEBP y los videos muestran la miniatura generada en el celular del invitado
  si existe; las fotos JPG, PNG y WEBP sin miniatura muestran el original con carga diferida; las
  fotos HEIC y los videos sin miniatura muestran un ícono de tipo de archivo. No hay procesamiento
  de archivos en el servidor después de la subida. La cuadrícula MUST cargar de forma progresiva (por bloques al hacer scroll o por páginas) y MUST NOT
  ofrecer filtros ni búsqueda en la fase 1.
- **FR-026a**: Al subir una foto JPG, PNG o WEBP o un video, la página de invitados MUST intentar
  generar en el celular una miniatura (imagen pequeña; en los videos, un fotograma) y subirla junto
  al archivo; si no lo logra, el archivo se sube sin miniatura. Para las fotos HEIC no se genera
  miniatura.
- **FR-027**: Los novios MUST poder abrir cada archivo en tamaño completo (o reproducir el video) y
  descargar el original individualmente.
- **FR-028**: Los novios MUST poder eliminar fotos y videos, con confirmación previa.
- **FR-029**: Los novios MUST poder cerrar la recepción antes de la hora prevista; el cierre MUST
  persistir y aplicarse de inmediato.
- **FR-030**: La sesión de los novios MUST expirar tras un periodo de inactividad (8 horas) y MUST
  poder cerrarse manualmente.
- **FR-031**: Deben existir exactamente dos cuentas administrativas (una para Mara y otra para
  Daniel), creadas mediante un procedimiento técnico controlado (no hay registro desde la
  interfaz). El restablecimiento de una contraseña perdida MUST hacerse manualmente con ese mismo
  procedimiento técnico; no hay flujo de autoservicio ni envío de correos en la fase 1.

**Monitoreo**

- **FR-030a**: El sistema MUST enviar una alerta por correo a un responsable técnico designado
  (configurable, distinto de los novios) cuando el servicio deje de responder a las verificaciones
  de salud durante más de 5 minutos o cuando las solicitudes de autorización de subida fallen con
  error del servidor en más del 5% durante 5 minutos. MUST avisar también cuando el problema se
  resuelva.
- **FR-030b**: La galería de los novios MUST mostrar un contador de archivos recibidos (total,
  fotos y videos) y la hora de la última subida completada.

**Disponibilidad y retención**

- **FR-032**: Los archivos MUST poder consultarse y descargarse hasta el final del 1 de diciembre de
  2026 (23:59, hora de Cali); después la galería MUST informar que el periodo terminó y no permitir
  ver ni descargar.
- **FR-033**: Después del fin del periodo de disponibilidad, los originales y miniaturas MUST
  conservarse en el almacenamiento privado hasta nuevo aviso de los novios. No hay borrado
  automático. La eliminación definitiva se hará manualmente, por el responsable técnico, cuando
  los novios lo pidan. Las cargas multipart incompletas MUST seguir limpiándose automáticamente.

**Fuera de alcance (fase 1)**

- **FR-034**: La fase 1 MUST NOT incluir cuentas de invitados, comentarios o likes, edición o filtros,
  reconocimiento facial, publicación en redes sociales, descarga masiva en ZIP ni filtros o
  búsqueda en la galería.

### Key Entities

- **Evento**: la boda; tiene identificador, nombre, fecha, inicio y cierre de la ventana de
  recepción, indicador de cierre anticipado (con fecha/hora y quién lo cerró) y fecha de fin de
  disponibilidad.
- **Sesión de subida**: conjunto de archivos enviados desde un mismo navegador de celular durante la
  ventana de recepción; tiene identificador emitido por el servidor, el último nombre declarado, la
  fecha de inicio y los contadores de archivos y bytes autorizados
  (para aplicar el tope de FR-012a).
- **Archivo multimedia**: foto o video del evento; tiene identificador del sistema, nombre original,
  tipo, tamaño, fecha/hora de subida, nombre declarado del invitado vigente al subirlo, estado
  (en progreso, completado, eliminado), sesión de subida asociada y miniatura cuando exista (fotos
  JPG, PNG y WEBP y videos, generada en el celular del invitado).
- **Usuario administrador**: novio con acceso al front-end privado; tiene nombre de usuario, hash de
  contraseña, contador de intentos fallidos y bloqueo temporal.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un invitado pasa de escanear el QR a tener su primera foto subida en menos de
  1 minuto con red móvil 4G.
- **SC-002**: Al menos 95% de los archivos válidos que los invitados intentan subir quedan
  almacenados correctamente, contando reintentos.
- **SC-003**: Una foto típica de celular (≤ 10 MB) termina de subir en menos de 15 segundos en
  red 4G.
- **SC-004**: El sistema soporta al menos 50 invitados subiendo al mismo tiempo sin fallos ni
  degradación perceptible.
- **SC-005**: Cero autorizaciones de subida emitidas fuera de la ventana de recepción o después de
  un cierre anticipado.
- **SC-006**: Cero archivos accesibles sin autorización (verificado intentando abrir archivos sin
  sesión).
- **SC-007**: Los novios abren y descargan cualquiera de los archivos más recientes en menos de
  30 segundos desde que inician sesión, y cada bloque adicional de miniaturas aparece en menos de
  3 segundos al hacer scroll en red 4G o wifi.
- **SC-008**: El 100% de la lista de chequeo manual (constitución) se completa en producción antes
  del 19 de noviembre de 2026.
- **SC-009**: Un mes después del evento no quedan cargos residuales por subidas incompletas ni por
  infraestructura sin usar. El almacenamiento de los originales y miniaturas conservados hasta
  nuevo aviso es un costo esperado y no cuenta como cargo residual.
- **SC-010**: Ante una caída del servicio durante la ventana de recepción, el responsable técnico
  recibe la alerta en menos de 10 minutos.

## Assumptions

- Existe un único evento (la boda); no se requiere manejar varios eventos en la fase 1, aunque el
  almacenamiento se organiza por evento.
- La recepción abre el jueves 19 de noviembre de 2026 a las 00:00 hora de Cali (el documento indica
  el día, no la hora).
- El periodo de disponibilidad incluye todo el 1 de diciembre de 2026 hasta las 23:59 hora de Cali
  (interpretación provisional indicada en el documento de requerimientos).
- Una transferencia ya iniciada antes del cierre puede continuar brevemente; no se exige bloquear
  escrituras al minuto exacto más allá de rechazar la finalización de subidas en partes.
- Los invitados no ven la galería ni los archivos de otros invitados; solo los novios la consultan.
- No se convierten HEIC ni se procesan fotos o videos en el servidor; las únicas miniaturas
  derivadas son las de fotos JPG, PNG y WEBP y las de videos, generadas en el celular del invitado.
- La eliminación por parte de los novios es definitiva (no hay papelera).
- No hay eliminación automática del material tras el 1 de diciembre; solo se limpian
  automáticamente las cargas multipart incompletas. La eliminación definitiva es manual, a pedido
  de los novios.
- Los novios deben ser avisados en la galería (p. ej. un banner) de la fecha límite de descarga. El
  banner también informa que, después del 1 de diciembre, el material solo se puede recuperar
  pidiéndolo al responsable técnico.
- El tope por sesión frena el abuso casual, no a un atacante decidido que abra sesiones nuevas;
  sin cuentas de invitado esto se acepta para la fase 1.
- Volumen esperado: del orden de 100–300 invitados y de 2.000 a 5.000 archivos en total.
- Los parámetros de bloqueo del login (5 intentos, 15 minutos) y de inactividad de sesión (8 horas)
  son valores por defecto razonables, ajustables en el plan.
- Dependencias: sin dominio propio; la URL del QR y el front-end privado usan los dominios HTTPS
  de AWS Amplify, y el API el dominio de CloudFront. Región: us-east-1 (constitución v2.0.0).
