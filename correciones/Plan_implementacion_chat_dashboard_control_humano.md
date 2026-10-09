# Plan de implementación: chat en tiempo real y control humano desde el dashboard

Proyecto: Tienda Virtual Multicanal · 8 de octubre de 2026

## 1. Objetivo y alcance

Desde el dashboard poder: ver las conversaciones de los clientes en tiempo real, **tomar el control de un chat** (el bot deja de contestar en ese chat), escribirle al cliente tú mismo por el mismo canal y **devolver el chat al bot** cuando quieras, conservando historial y contexto.

**Principio de trabajo:** se modifica lo que ya existe (migraciones V002 y V003, `ExposedConversationStore`, `InboundMessagePipeline`, `ChannelRouter`, rutas y renderers). No se duplican fuentes de datos.

**Incluye:** bandeja de conversaciones, panel de chat, tomar/devolver control, envío humano con estados de envío, tiempo real con SSE, aviso de «requiere atención» y ventana de 24 horas.

**Primera versión, no incluye:** varios operadores con control compartido, ver imágenes/comprobantes de pago dentro del chat, estados «entregado/leído» del cliente, y etiquetas, notas internas o asignaciones.

**Base del plan:** lectura parcial del código. Lo no verificado queda en la Fase 0.

## 2. Punto de partida: lo que ya existe

| Pieza | Dónde | Qué aporta |
| --- | --- | --- |
| Estado `ESCALATED_HUMAN` | `InboundMessagePipeline.decide` | El bot guarda el mensaje del cliente y no responde |
| Envío por canal | `ChannelRouter` y adaptadores de WhatsApp, Instagram y Messenger | `sendMessage(canal, destinatario, texto)` ya funciona |
| Candado por conversación | `lockFor(convId)` (64 mutex) | Serializa decisiones del bot por conversación, solo dentro de un proceso |
| Idempotencia de entrada | `ProcessedEventStore` y `InboundWebhookWorker` | Webhooks repetidos no se procesan dos veces |
| Ecos de Meta | `MetaWebhookReceiver` ignora `is_echo` | Los mensajes que enviamos no regresan como entrantes |
| Persistencia | `ExposedConversationStore` y tabla `conversaciones` | Estado de negociación, reserva y mensajes |
| Seguridad | `DashboardGuard`, `OriginGuard`, `AuthThrottle` | Autenticación y protección contra CSRF en POST |
| Usuario en auditoría | `DatabaseFactory.tx` | Fija `app.usuario` y `app.origen` por transacción |

### Problemas del código actual que este plan corrige

1. **Los mensajes viven en un JSON** (`conversaciones.mensajes`) y `save()` reescribe la fila completa. Si el cliente y tú escriben a la vez, un mensaje se pierde. Y si el bot cargó la conversación antes de que tomaras el control, su `save` posterior puede pisar la marca de «atención humana».
2. **`handle()` envía fuera del candado.** Decide la respuesta bajo `lockFor`, suelta el candado y recién entonces llama a `router.sendMessage`. Entre ambos pasos puedes tomar el control y el bot enviaría igual.
3. **El LLM se consulta antes del candado.** `parseIntent` (Gemini) corre aunque el chat esté en manos humanas: gasto y latencia innecesarios.
4. **`ESCALATED_HUMAN` vuelve solo a `IDLE`** al vencer `escalationTimeout` (24 h). Reutilizarlo como «modo humano» devolvería el chat al bot en silencio, y además mezcla «el cliente pidió asesor» con «yo tengo el control».
5. **Al devolver el chat, el bot quedaría mudo** si el estado sigue en `ESCALATED_HUMAN`, porque esa rama nunca responde. Hay que definir a qué estado vuelve.

## 3. Decisiones de diseño

1. **Modo de atención independiente del estado de negociación.** Columnas nuevas en `conversaciones`: `atencion` (`BOT` o `HUMANO`, por defecto `BOT`), `atendido_por`, `atencion_desde`, `requiere_atencion` (boolean). El estado de venta (`IDLE`, `RESERVED`, `PAYMENT_PENDING`…) no se toca al tomar el control, así no se pierde una reserva ni un pago pendiente.
2. **Esas columnas nunca se escriben desde `save()`.** `ExposedConversationStore.save` deja de incluirlas en su UPDATE de fila completa; solo cambian mediante funciones SQL propias. Así una copia vieja de la conversación no puede revertir el modo.
3. **Tabla de mensajes de solo inserción** `conversacion_mensajes`: `id` (bigserial), `conversation_id`, `canal_msg_id` (id del mensaje en el canal), `emisor` (`CLIENTE`, `BOT`, `HUMANO`, `SISTEMA`), `contenido`, `estado_envio` (`RECIBIDO`, `ENVIANDO`, `ENVIADO`, `FALLO`), `error`, `creado_en`, `enviado_en`. Índice único `(conversation_id, canal_msg_id)` para la idempotencia. Esta tabla pasa a ser la fuente de verdad de los mensajes.
4. **La compuerta del bot vive en la base de datos.** Antes de enviar una respuesta automática, el bot la registra con `tienda.registrar_mensaje_bot(...)`: inserta el mensaje como `ENVIANDO` **solo si** `atencion = 'BOT'` en esa misma sentencia; si el chat ya está en modo humano, no inserta y la respuesta se descarta. Funciona aunque haya varias instancias y no depende del candado en memoria.
5. **Límite honesto de la carrera.** Ninguna respuesta del bot *se inicia* después de que tomas el control. Un mensaje que el bot ya había entregado al canal en el instante exacto del clic no se puede recuperar. El panel lo avisa: «el bot tenía un mensaje en envío cuando tomaste el control».
6. **Tiempo real por SSE con respaldo en base de datos.** Una tabla `chat_eventos` (`id` bigserial, `conversation_id`, `tipo`, `ref_id`) la alimentan triggers sobre `conversacion_mensajes` y `conversaciones`. El flujo SSE lee esa tabla desde el último id visto. Las notificaciones en memoria solo despiertan al lector antes; si se pierden, lo recoge el sondeo de 2 segundos. Así sobrevive a reconexiones y no depende de `LISTEN/NOTIFY` (poco fiable con el pooler de Supabase).
7. **Reconexión sin pérdida.** `EventSource` envía `Last-Event-ID` solo. El servidor reenvía los eventos posteriores; si el cursor es demasiado viejo (eventos purgados), envía `reset` y el cliente recarga la bandeja.
8. **Los mensajes del cliente se pintan como texto, nunca como HTML.** El cliente de JavaScript construye el DOM con `textContent`. El servidor no incrusta contenido de clientes en HTML.
9. **Sin devolución automática por tiempo.** El modo humano dura hasta que lo devuelvas. En su lugar, aviso visible cuando un chat en tu control lleva más de N horas sin respuesta tuya (configurable).
10. **«Requiere atención»** se enciende cuando el pipeline escala (pidió asesor, envió comprobante) y se apaga cuando tomas el chat o respondes.

## 4. Fases

### Fase 0 — Auditoría (esfuerzo: pequeño)

- Para qué usa el pipeline `conv.messages` (¿contexto del LLM? ¿cuántos mensajes?), porque ese dato pasará a leerse de la tabla nueva.
- Versión de Ktor en `libs.versions.toml` y si está disponible el plugin SSE; si no, se usa `respondTextWriter` con `text/event-stream`.
- Qué hay delante del servidor (Docker, proxy, Cloudflare): algunos almacenan en búfer las respuestas y rompen SSE. Se envía el encabezado `X-Accel-Buffering: no` y latidos cada 15 s.
- Si el navegador reutiliza las credenciales Basic en `EventSource` del mismo origen (normalmente sí; verificar en el despliegue real).
- Qué llega hoy por el webhook cuando el cliente manda imagen, audio o documento (¿texto vacío?, ¿se pierde el id del medio?).
- Si `WhatsAppMessagingAdapter` ya tiene envío de plantillas (su documentación menciona plantillas de 24 h) y qué plantillas hay en `docs/whatsapp-templates`.
- Qué tipos de resultado tiene `PortResult` (en especial si distingue fallos transitorios de permanentes).
- Si hay más de una instancia del servidor desplegada.
- El esquema `tienda` de la base destino debe estar vacío: `DatabaseFactory` usa `baselineOnMigrate(true)` y un esquema con tablas antiguas haría que Flyway omita V001.

### Fase 1 — Modificación del esquema existente (esfuerzo: medio)

Se editan directamente V002 (conversaciones y mensajes) y V003 (eventos), que aún no se han aplicado a ninguna base real.

- **`conversaciones`:** columnas `atencion` (CHECK en `BOT`/`HUMANO`), `atendido_por`, `atencion_desde`, `requiere_atencion`, `ultimo_entrante_en` (último mensaje del cliente, para la ventana de 24 h).
- **`conversacion_mensajes`:** tabla descrita en la sección 3, con índice único de idempotencia y RLS como el resto de tablas (`fn_asegurar_rls`).
- **Funciones SQL:**
  - `tienda.tomar_conversacion(conversation_id, operador)`: pasa a `HUMANO` de forma atómica, apaga `requiere_atencion` y devuelve si había un mensaje del bot en `ENVIANDO`.
  - `tienda.devolver_conversacion(conversation_id, operador, nuevo_estado)`: pasa a `BOT` y, si el estado era `ESCALATED_HUMAN`, lo lleva al estado indicado.
  - `tienda.registrar_mensaje_cliente(...)`: inserta idempotente, actualiza `ultimo_entrante_en`.
  - `tienda.registrar_mensaje_bot(...)`: la compuerta de la decisión 4.
  - `tienda.registrar_mensaje_humano(...)`: inserta como `ENVIANDO` solo si `atencion = 'HUMANO'`.
  - `tienda.marcar_envio(mensaje_id, estado, error, id_externo)`: cierra el envío como `ENVIADO` o `FALLO`.
- **`chat_eventos`** y sus triggers (inserción y cambio de estado en mensajes; cambio de `atencion` o `requiere_atencion` en conversaciones). Limpieza de eventos de más de 7 días.
- **Historial:** los cambios de modo quedan en el historial genérico con operador (`app.usuario`).
- **`anonimizar_cliente`:** debe borrar los mensajes de la tabla nueva, no solo el JSON.
- **Vista `v_bandeja`:** conversación, cliente, canal, último mensaje y hora, `atencion`, `requiere_atencion`, mensajes sin responder, `ultimo_entrante_en`, estado de negociación y producto activo.

### Fase 2 — Backend del chat (esfuerzo: medio)

- **`ExposedConversationStore`:** `save` deja de escribir el JSON y las columnas de atención; carga los últimos N mensajes desde `conversacion_mensajes` para armar `Conversation.messages`.
- **Entrada:** el webhook inserta el mensaje del cliente con `registrar_mensaje_cliente` **antes** de cualquier otra cosa. Idempotente por id del canal: un webhook repetido no duplica.
- **`InboundMessagePipeline.handle`:** tras persistir el mensaje, si `atencion = 'HUMANO'` termina ahí, sin llamar a `parseIntent` ni al LLM. Si está en `BOT`, sigue como hoy; cuando hay respuesta, la registra con `registrar_mensaje_bot` y solo envía si la compuerta lo permitió, y luego cierra con `marcar_envio`.
- **Escalaciones del bot** (pidió asesor, comprobante de pago): además de pasar a `ESCALATED_HUMAN`, encienden `requiere_atencion`.
- **Respuestas del bot en tarjetas de producto:** también pasan por la compuerta y se registran como mensajes `BOT`.

### Fase 3 — Control humano (esfuerzo: medio)

Nuevo `ChatRoutes.kt`. Todas las rutas que modifican datos pasan por `DashboardGuard` con `mutating = true`.

| Ruta | Acción |
| --- | --- |
| `GET /api/chat/bandeja` | Lista de conversaciones con filtros (todas, requieren atención, en mi control) |
| `GET /api/chat/{id}/mensajes` | Historial en orden, paginado hacia atrás |
| `POST /api/chat/{id}/tomar` | Tomar la palabra |
| `POST /api/chat/{id}/devolver` | Devolver al bot (con opción «que el bot responda al último mensaje», apagada por defecto) |
| `POST /api/chat/{id}/enviar` | Enviar mensaje del operador (con id de cliente para idempotencia) |
| `POST /api/chat/mensajes/{id}/reintentar` | Reintentar un mensaje en `FALLO`, reutilizando la misma fila |

Reglas del envío humano:

- Solo se puede enviar si el chat está en `HUMANO`; si no, se rechaza con un mensaje claro.
- El envío se hace con `ChannelRouter`; el resultado se guarda con `marcar_envio`. Si el canal no está configurado o falla, se muestra el fallo; nunca se simula un envío.
- **Ventana de 24 horas:** si pasaron más de 24 h desde `ultimo_entrante_en`, no se permite texto libre en WhatsApp, Instagram ni Messenger. En WhatsApp se ofrece enviar una plantilla aprobada, si la Fase 0 confirma que el adaptador la soporta. En Messenger e Instagram existen etiquetas de agente humano de hasta 7 días, pero requieren permisos aprobados; verificar si los tienes antes de ofrecerlas.
- Límite de longitud por canal y limitación de ritmo de envíos (`AuthThrottle` como referencia).
- **A qué estado vuelve el bot** (parámetro `nuevo_estado` de devolver): propuesta, `IDLE` si no hay reserva activa; si la hay, el estado que corresponda a esa reserva. Se confirma en la Fase 0 revisando `escalate()`, que hoy no guarda el estado previo.
- Devolver al bot **no** confirma pagos ni acciones sensibles pendientes: la confirmación de pago sigue siendo una acción explícita del operador (`ConfirmPaymentUseCase`).
- El mensaje del operador se guarda con `emisor = HUMANO` y `app.usuario`.

### Fase 4 — Tiempo real con SSE (esfuerzo: medio)

- `GET /api/chat/stream`: protegido por `DashboardGuard`, con `Content-Type: text/event-stream`, `Cache-Control: no-cache` y `X-Accel-Buffering: no`.
- Cada evento lleva `id` = `chat_eventos.id` y un tipo (`mensaje`, `envio`, `atencion`, `bandeja`) con solo identificadores; el cliente pide el detalle por id.
- Bucle por cliente: esperar la señal en memoria o 2 s, consultar `chat_eventos` con `id > cursor` (máximo 200) y enviar. Latido cada 15 s.
- `Last-Event-ID` para reconectar; `reset` si el cursor ya fue purgado.
- Límite de conexiones simultáneas (por ejemplo, 5) para evitar fugas si el navegador abre varias.
- Cierre ordenado al detener el servidor.

### Fase 5 — Interfaz (esfuerzo: grande)

Nuevo `ChatRenderer.kt` y un script propio; no se agrega más código al `DashboardRenderer.kt`.

- **Bandeja:** cliente, canal, último mensaje, hora, contador de no leídos y etiqueta de estado: «Bot atendiendo», «Atención humana», «Requiere atención». Orden por actividad; los que requieren atención arriba. Contador en el título de la pestaña y sonido opcional.
- **Panel de conversación:** historial en orden, desplazamiento al último mensaje, indicador de quién responde, caja de texto, botones «Tomar la palabra» y «Devolver al bot». El campo de texto solo se habilita en modo humano.
- **Estados de envío por mensaje:** enviando, enviado, fallo con botón «Reintentar». Los mensajes del bot y del operador se distinguen visualmente de los del cliente.
- **Panel lateral:** producto activo, precio acordado, reserva y estado de negociación; enlace a «Registrar venta» cuando exista el módulo de ventas.
- **Avisos claros:** ventana de 24 horas vencida, canal no configurado, bot con mensaje en envío al tomar el control, conexión perdida («reconectando…»).
- **Seguridad en el cliente:** todo contenido de clientes se inserta con `textContent`; ningún `innerHTML` con datos de mensajes.
- Rutas de chat fallan cerradas: si `DASHBOARD_PASSWORD` está vacío, el módulo de chat no se habilita, porque expondría datos personales.

### Fase 6 — Pruebas (esfuerzo: medio)

Las funciones SQL requieren PostgreSQL real (Testcontainers o el `docker-compose` del proyecto), además de las pruebas actuales.

- Mensaje nuevo del cliente: aparece en el panel sin recargar y sigue ahí tras reconectar con `Last-Event-ID`.
- Tomar la palabra: desde ese momento ninguna respuesta del bot sale en ese chat; los mensajes del cliente se guardan y se muestran.
- **Carrera:** el bot prepara una respuesta, el operador toma el control antes del envío: la compuerta descarta la respuesta y no sale ningún mensaje.
- **Carrera en el instante exacto:** el bot ya había enviado; el panel lo indica al tomar el control.
- Mensaje simultáneo del cliente y del operador: ambos quedan guardados.
- `save()` con una copia vieja de la conversación no revierte `atencion`.
- Devolver al bot: la siguiente respuesta del cliente recibe respuesta automática con el contexto correcto; con estado `ESCALATED_HUMAN` no queda mudo.
- Webhook repetido: no duplica mensajes; reintento de envío humano: no duplica el mensaje.
- Envío fallido: se muestra `FALLO`, el reintento reutiliza la misma fila.
- Chat fuera de la ventana de 24 horas: se bloquea el texto libre con aviso.
- Mensaje de cliente con HTML o script: se ve como texto.
- SSE: latidos, `reset` con cursor viejo, límite de conexiones, rechazo sin autenticación.
- Con `DASHBOARD_PASSWORD` vacío el módulo de chat no se habilita.
- Anonimización de un cliente borra sus mensajes de la tabla nueva.

### Fase 7 — Despliegue (esfuerzo: pequeño)

1. Probar el esquema completo sobre una base vacía; si ya corriste las migraciones en alguna base de desarrollo, recrearla (Flyway rechaza migraciones modificadas después de aplicadas).
2. Verificar SSE detrás del proxy o hosting real antes de dar el módulo por bueno.
3. Primero con un número de prueba en cada canal: tomar, escribir, devolver.
4. Vigilar durante la primera semana: mensajes en `FALLO`, chats en modo humano sin respuesta y tamaño de `chat_eventos`.
5. Reversión: ocultar el módulo de chat; el bot sigue funcionando porque `atencion` es `BOT` por defecto.

## 5. Criterios de terminado

- Los mensajes nuevos aparecen en el dashboard sin recargar y no se pierden tras una desconexión.
- Al tomar la palabra, el bot no responde en ese chat; yo escribo y el cliente lo recibe por el canal correcto.
- Si el bot estaba preparando una respuesta al tomar el control, esa respuesta se descarta.
- Al devolver al bot, retoma con el historial y el contexto, sin quedarse mudo.
- Los fallos de envío se ven y los reintentos no duplican mensajes.
- El dashboard avisa cuando la ventana de 24 horas impide escribir.
- Todo es persistente, autenticado y auditable (quién tomó, quién devolvió, cuándo).
- Las pruebas existentes siguen pasando y se agregan las nuevas.

## 6. Riesgos y medidas

| Riesgo | Medida |
| --- | --- |
| Bot y operador responden a la vez | Compuerta en base de datos (`registrar_mensaje_bot`) y modo humano que `save()` no puede pisar |
| Mensajes perdidos por guardar todo en un JSON | Tabla de solo inserción con idempotencia |
| Dashboard desactualizado tras una caída de conexión | Eventos persistidos y `Last-Event-ID` |
| SSE bloqueado por proxy o hosting | Encabezado sin búfer, latidos y prueba en el entorno real; plan B: sondeo corto del mismo endpoint |
| Bot mudo tras devolver el chat | `nuevo_estado` explícito al devolver |
| Texto libre fuera de la ventana de 24 h | Bloqueo previo con aviso y plantilla aprobada cuando exista |
| Contenido malicioso en mensajes de clientes | Pintar solo con `textContent`; sin HTML de cliente en el servidor |
| Exposición de datos personales por tablero abierto | El chat no se habilita sin contraseña |
| Chat olvidado en modo humano | Aviso por inactividad; sin devolución silenciosa |
| Muchas conexiones SSE abiertas | Límite de conexiones y cierre ordenado |

## 7. Decisiones pendientes

1. **¿Qué hacer con un chat que olvidas en modo humano?** Propuesta: solo aviso tras N horas (¿cuántas?), sin devolución automática.
2. **Al devolver con un mensaje del cliente sin responder:** ¿que el bot lo conteste automáticamente o esperar al siguiente mensaje? Propuesta: esperar, con casilla opcional para responder.
3. **Plantillas de WhatsApp fuera de la ventana de 24 h:** ¿tienes plantillas aprobadas y quieres ofrecerlas desde el dashboard?
4. **Imágenes y comprobantes de pago:** hoy el bot solo entiende texto. ¿Quieres verlos en el chat en una segunda etapa? Requiere descargar el medio por la API de Meta.
5. **Un solo operador:** ¿basta con el usuario único del dashboard, o necesitarás identificar a más de una persona más adelante?
6. **Retención:** ¿cuánto tiempo conservar los mensajes antes de borrarlos, según tu política de privacidad?

## 8. Orden sugerido

Fase 0, luego Fases 1 y 2 juntas (la base del chat), Fase 3, Fase 4, Fase 5, y por último 6 y 7. Conviene implementarlo **después** del módulo de ventas manuales: son independientes, pero ventas deja ya la auditoría por operador y el patrón de rutas y renderers.

## 9. Segunda etapa posible

- Ver imágenes, audios y comprobantes de pago dentro del chat.
- Estados «entregado» y «leído» del cliente, con los webhooks de estado de WhatsApp.
- Varios operadores con asignación de chats y conflictos explícitos.
- Notas internas y etiquetas por conversación.
- Botón «Registrar venta» desde la conversación, pasando `reserva_id` y `cliente_id` a la función de ventas.
