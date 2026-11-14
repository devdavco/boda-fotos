# Requerimientos: Fotos y videos de la boda

## 1. Resumen

Los invitados de la boda de Mara y Daniel (21 de noviembre de 2026, Cali, Colombia) escanean un código QR impreso o mostrado en el evento. El QR abre una página web donde pueden subir fotos y videos desde sus celulares. Los archivos se almacenan en Amazon S3. Los novios cuentan con un front-end privado e independiente para consultar y descargar el material del evento.

## 2. Objetivos

- Permitir que cualquier invitado suba fotos y videos desde su celular, sin crear una cuenta.
- Recibir archivos desde el jueves 19 de noviembre de 2026 hasta el domingo 22 de noviembre de 2026 a las 12:00 p. m., hora de Cali (UTC-05:00).
- Dar a los novios un front-end privado, separado del de invitados, para consultar y descargar el material.
- Mantener fotos y videos disponibles hasta 10 días después de la boda.
- Mantener los archivos privados, la experiencia de subida simple y los costos adecuados para un evento.

## 3. Usuarios

- **Invitados:** escanean el QR, indican su nombre y suben fotos o videos. No crean una cuenta ni inician sesión; el nombre es información declarada por el invitado y no verifica su identidad.
- **Novios (administradores):** ingresan al front-end privado con usuario y contraseña para consultar, descargar y, si es necesario, eliminar archivos.

## 4. Requerimientos funcionales

### RF-01 Acceso mediante QR
- Debe existir una URL única para el evento que se pueda codificar en un QR.
- El QR debe abrir la página directamente desde el celular, sin instalar nada.

### RF-02 Front-end de invitados y subida
- Página responsiva, pensada primero para celular.
- Permitir seleccionar fotos y videos desde la galería o capturar contenido en el momento, sujeto a las capacidades del navegador y dispositivo.
- Permitir seleccionar varios archivos a la vez.
- Mostrar el progreso por archivo y confirmar cuáles terminaron correctamente.
- Permitir reintentar archivos cuya subida haya fallado sin volver a seleccionar los que ya se subieron correctamente.
- El invitado debe indicar su nombre antes de subir. El nombre se asocia a los archivos de esa sesión de subida, sin presentarlo como identidad verificada.
- La subida debe estar habilitada desde el jueves 19 de noviembre de 2026 hasta el domingo 22 de noviembre de 2026 a las 12:00 p. m., hora de Cali (UTC-05:00; zona IANA `America/Bogota`). Fuera de ese periodo, la página debe informar que la recepción está cerrada y el servidor no debe emitir nuevos permisos de subida.
- Las URLs prefirmadas deben expirar a más tardar en el cierre. Los archivos grandes se completan mediante una operación de finalización controlada por el servidor, que debe rechazar finalizaciones después del cierre. Una transferencia que ya empezó antes del cierre puede continuar brevemente; si se requiere impedir cualquier escritura posterior al minuto límite, eso debe resolverse con una validación adicional del flujo de recepción.
- Los administradores pueden cerrar la recepción antes de la hora prevista si lo necesitan.

### RF-03 Formatos y límites
- Formatos de imagen permitidos: JPG, JPEG, PNG, HEIC y WEBP.
- Formatos de video permitidos: MP4 y MOV.
- Tamaño máximo: 40 MB por foto y 500 MB por video.
- Validar tipo y tamaño antes de emitir permisos y rechazar la subida si el archivo excede los límites. La validación del navegador debe complementarse con controles del lado servidor.
- Para videos de 100 MB o más, usar carga multipart a S3 para permitir reintentar partes fallidas sin reiniciar todo el archivo. Cancelar o limpiar cargas multipart incompletas para evitar almacenamiento y costos residuales.

### RF-04 Almacenamiento en S3
- Cada archivo se guarda en un bucket privado de Amazon S3, organizado por evento. El sistema genera la clave/nombre del objeto; el nombre original se conserva como metadato cuando sea necesario.
- La subida va directamente del navegador a S3 mediante URL prefirmada de corta duración, sin hacer pasar los archivos por el servidor de la aplicación.
- El servidor solo debe emitir permisos para tipos, tamaños, claves y periodo de recepción autorizados.
- No se debe sobrescribir un archivo existente por una colisión de nombres.
- Los objetos deben quedar disponibles para los novios hasta el 1 de diciembre de 2026, diez días después de la fecha de boda. La hora exacta de expiración de ese día queda por confirmar.
- Al terminar el periodo, las fotos y videos dejan de estar disponibles en la galería. La eliminación permanente de originales y derivados debe seguir la política de retención que se confirme.

### RF-05 Front-end privado de los novios
- Debe ser un front-end separado del de invitados, con acceso autenticado y disponible solo para los novios.
- Los novios inician sesión con usuario y contraseña.
- La tabla de usuarios en Amazon DynamoDB guarda el nombre de usuario y un hash de contraseña generado con una función diseñada para contraseñas y salt único por usuario; nunca almacena la contraseña en texto claro ni cifrada de forma reversible. DynamoDB cifra los datos de tabla en reposo.
- El acceso a DynamoDB se realiza desde el backend con permisos de mínimo privilegio; el navegador no accede directamente a la tabla.
- Proteger el inicio de sesión frente a intentos repetidos y no revelar si falló el usuario o la contraseña.
- Vista en cuadrícula con miniaturas y opción de ver en tamaño completo.
- Mostrar fotos y videos recibidos para el evento, junto con la fecha de subida.
- El proceso de recuperación o restablecimiento de contraseña debe definirse antes del despliegue.

### RF-06 Descarga
- Los novios pueden descargar fotos y videos individuales desde su front-end privado.
- La descarga de archivos ZIP no forma parte de la primera versión.

### RF-07 Administración
- Los novios pueden eliminar fotos no deseadas.
- Los novios pueden cerrar la recepción antes del domingo al mediodía.

## 5. Requerimientos no funcionales

- **Seguridad:** el bucket no es público. La lectura y subida de archivos se autorizan mediante URLs firmadas de corta duración; el front-end de administración requiere usuario y contraseña. La contraseña se almacena como hash con salt, no como texto claro ni cifrado reversible. Los permisos de S3 y DynamoDB deben limitarse a las operaciones necesarias.
- **Privacidad:** la página debe mostrar un aviso sobre el uso de las fotos. Las fotos no se publican en internet ni se comparten fuera de la galería privada.
- **Costo:** estimar antes del despliegue el almacenamiento, las solicitudes de S3 y la transferencia de datos de las descargas. Usar S3 Standard como opción inicial por tratarse de archivos que se consultarán y descargarán pronto; revisar clase y retención cuando se defina cuánto tiempo conservarlos.
- **Disponibilidad:** los front-ends deben funcionar durante el periodo de recepción. Los archivos deben seguir disponibles para los novios hasta el 1 de diciembre de 2026; después dejan de estar disponibles en la galería. La eliminación permanente queda sujeta a la política de retención que se defina.
- **Rendimiento:** la subida debe ser directa a S3, estable en conexiones móviles y permitir reintentos. La región debe elegirse para reducir la distancia de red entre los invitados, la aplicación y el bucket.
- **Compatibilidad:** funciona en las versiones recientes de Chrome, Safari (iOS) y Samsung Internet.
- **Idioma:** interfaz en español.

## 6. Propuesta inicial de AWS

- Usar Amazon S3 en una sola región como almacenamiento de fotos y videos, con el front-end y los componentes que emiten las URLs prefirmadas en esa misma región.
- Para Cali, evaluar primero AWS México (Central, `mx-central-1`) como candidata por cercanía geográfica y comparar latencia real desde redes móviles en Cali con AWS EE. UU. Este (N. Virginia, `us-east-1`) y AWS Sudamérica (São Paulo, `sa-east-1`). La región final debe basarse en pruebas de latencia y confirmar que los servicios requeridos estén disponibles; la distancia geográfica por sí sola no garantiza menor latencia.
- Mantener la subida directa desde el navegador al endpoint regional de S3 para evitar un salto y procesamiento innecesarios a través del servidor. Esto ayuda a reducir latencia y carga de aplicación, aunque no elimina el efecto de la distancia física ni de la red móvil.
- No habilitar S3 Transfer Acceleration por defecto: puede ayudar con subidas a larga distancia, pero añade cargos. Evaluarlo solo si los invitados estarán distribuidos geográficamente y las pruebas desde sus redes muestran una mejora que justifique el costo.
- Mantener el bucket privado, habilitar bloqueo de acceso público y cifrado en tránsito y en reposo. Aplicar políticas de mínimo privilegio y revisar los cargos regionales antes del despliegue.
- Usar Amazon DynamoDB para los usuarios administradores y almacenar contraseñas únicamente como hashes seguros con salt mediante una función diseñada para contraseñas. El cifrado en reposo de DynamoDB protege los datos de la tabla, pero no sustituye el hash de contraseñas.
- Configurar limpieza de cargas multipart incompletas y tener en cuenta el costo de almacenamiento, solicitudes, partes sin completar y transferencia de datos de descarga.

Referencias: [regiones y servicios de AWS](https://docs.aws.amazon.com/general/latest/gr/rande.html), [prácticas de rendimiento de Amazon S3](https://docs.aws.amazon.com/AmazonS3/latest/userguide/optimizing-performance.html), [carga con URLs prefirmadas](https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html), [carga multipart en Amazon S3](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html), [cifrado en reposo de DynamoDB](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/EncryptionAtRest.html) y [precios de Amazon S3](https://aws.amazon.com/s3/pricing/).

## 7. Fuera de alcance (fase 1)

- Cuentas de usuario para invitados.
- Comentarios o likes en las fotos.
- Edición o filtros de imagen.
- Reconocimiento facial.
- Publicación en redes sociales.
- Descarga de todas las fotos y videos en un único archivo ZIP.

## 8. Criterios de aceptación

- Un invitado puede abrir la página desde el QR y subir fotos y videos permitidos desde un celular, viendo progreso y resultado por archivo.
- Una subida interrumpida puede reintentarse sin volver a subir los archivos completados.
- La recepción acepta archivos desde el jueves 19 de noviembre hasta el domingo 22 de noviembre de 2026 a las 12:00 p. m. en `America/Bogota`; no se emiten permisos después del cierre y las finalizaciones de cargas multipart se rechazan después de esa hora.
- Los archivos quedan en el bucket privado, bajo claves generadas por el sistema, y no son accesibles públicamente.
- Los novios pueden autenticarse con usuario y contraseña en el front-end privado, consultar y descargar archivos individuales.
- Los novios pueden cerrar la recepción antes de la hora límite.
- Las fotos y videos pueden consultarse y descargarse hasta el 1 de diciembre de 2026; después dejan de estar disponibles en la galería.

## 9. Preguntas abiertas

1. ¿El periodo de disponibilidad termina al comenzar o al finalizar el 1 de diciembre de 2026? Mientras se confirma, se interpreta como disponible durante todo ese día en hora de Cali.
2. Después de esa fecha, ¿se deben eliminar permanentemente los originales y miniaturas o conservarse fuera de la galería?
3. ¿Cuántas cuentas administrativas se necesitan y cómo se restablecerá la contraseña si se pierde?
4. ¿Desde qué redes o ubicaciones se probará la latencia para escoger la región definitiva?
