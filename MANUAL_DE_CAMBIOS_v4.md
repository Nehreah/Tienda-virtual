# Manual de cambios v4 — Tienda Virtual Multicanal, Semiautogestionada

Continúa la entrega v3 (su manual quedó como `MANUAL_DE_CAMBIOS_v3.md`; los anteriores como `_v2` y `_v1`). Aquí solo se describe **lo nuevo de esta ronda**. Todo lo demás permanece igual.

> **Aviso de verificación (importante):** igual que en v1–v3, el entorno no tiene Gradle, Kotlin ni red, así que **no se compiló ni se ejecutaron las pruebas**. Esta ronda se hizo en modo prudente: primero se **releyó línea por línea el código de v3** (la ronda que más código nuevo traía y que nadie había compilado) y solo después se añadió un número pequeño de cambios. Lo que sí se verificó: balance de llaves/paréntesis, tipos y firmas contra las clases reales del proyecto, y la lógica de las expresiones regulares del intérprete (se simularon con casos de ejemplo). Antes de usar: `./gradlew test`. Si algo no compila, pega el mensaje y lo corrijo.

---

## 1. Resumen

| Tema | Estado |
|---|---|
| Revisión completa del código v3 (pipeline, `Application.kt`, pruebas) | ✅ No se encontraron errores de compilación evidentes; sí se encontraron los problemas de abajo |
| Fallo de seguridad en el tablero (datos de clientes sin escapar) | ✅ Corregido |
| Protección opcional del tablero y `/api/*` con contraseña | ✅ Hecho (opcional) |
| Pipeline: cifras absurdas (teléfonos, cédulas) no deben reservar | ✅ Hecho |
| Pipeline: mensaje correcto cuando llega un "comprobante" sin pago pendiente | ✅ Hecho |
| Intérprete: entiende `110 mil`, `110k`, `110,000` | ✅ Hecho |
| Acceso concurrente a `AppStore` en rutas del tablero, Excel, Shopify y vitrina | ✅ Sincronizado |
| `run_tests.py` imprimía "TODOS LOS TEST PASARON" sin ejecutar nada | ✅ Reescrito: ahora ejecuta Gradle de verdad |
| `local.properties.example` traía datos de pago de ejemplo | ✅ Corregido |
| Compilar y correr pruebas | ⏳ Tuyo (prioridad 1) |
| Persistencia, outbox, registrar venta, `MetaShopAdapter`, Gemini real, etc. | ⏳ No se tocaron (ver §5) |

---

## 2. Archivos entregados en esta ronda

| Archivo | Tipo |
|---|---|
| `app/.../dashboard/DashboardAuth.kt` | **Nuevo** |
| `app/src/test/.../dashboard/DashboardAuthTest.kt` | **Nuevo** (3 pruebas) |
| `app/src/test/.../dashboard/DashboardRendererTest.kt` | **Nuevo** (1 prueba) |
| `app/.../Application.kt` | Modificado |
| `app/.../dashboard/DashboardRenderer.kt` | Modificado (escape de HTML) |
| `app/.../pipeline/InboundMessagePipeline.kt` | Modificado (2 ajustes) |
| `app/src/test/.../pipeline/InboundMessagePipelineTest.kt` | Modificado (+3 pruebas) |
| `app/src/test/.../pipeline/GeminiLlmAdapterTest.kt` | Modificado (+3 pruebas) |
| `infra/.../llm/GeminiLlmAdapter.kt` | Modificado |
| `infra/.../config/AppConfig.kt` | Modificado (2 variables) |
| `shared-kernel/.../Money.kt` | Modificado (1 línea) |
| `local.properties.example` | Modificado |
| `run_tests.py` | Reescrito |
| `README.md` | Modificado (variables nuevas) |
| `DIAGRAMA_DE_CLASES.md` | Actualizado (v4); el anterior pasó a `DIAGRAMA_DE_CLASES_v3.md` |
| `MANUAL_DE_CAMBIOS.md` | Este manual (el anterior pasó a `_v3`) |

---

## 3. Qué se hizo, exactamente y por qué

### Cambio 16 — Datos de clientes sin escapar en el tablero (seguridad)
**Problema:** desde v3 el tablero muestra tratos reales, y su `id` (`<canal>:<idCliente>`) y su `channel` salen de lo que llega por el webhook. En `DashboardRenderer` esos campos (y `offeredPrice`, `dealStateLabel`, `timestamp`) se insertaban **sin escapar**. Con `META_APP_SECRET` vacío (hoy permitido) cualquiera podía enviar un `from` con HTML/JavaScript y quedaría ejecutándose en el navegador de quien abra el tablero (XSS almacenado), en un tablero que además no tenía contraseña.

**Ahora:** todos esos campos pasan por `escapeHtml`, que además escapa la comilla simple. También se escapa `p.key.value`. Prueba nueva: `DashboardRendererTest`.

### Cambio 17 — Contraseña opcional para el tablero y `/api/*` (`DashboardAuth`)
Antes, el tablero (que muestra costos, precios mínimos y ahora teléfonos de clientes), la subida/descarga de Excel y el simulador estaban abiertos a cualquiera que alcanzara el puerto.

- Nuevas variables: `DASHBOARD_USER` (por defecto `admin`) y `DASHBOARD_PASSWORD`.
- **Con `DASHBOARD_PASSWORD` definido:** `/`, `/api/sim/chat`, `/api/excel/upload` y `/api/excel/export` piden usuario y clave (HTTP Basic; el navegador muestra su ventana de acceso). Comparación en tiempo constante.
- **Sin `DASHBOARD_PASSWORD`:** todo sigue abierto como hasta ahora (para no dejarte fuera sin querer) y la consola muestra una **ADVERTENCIA**.
- No pasan por aquí: `/webhooks/meta` y `/webhooks/shopify` (ya tienen sus firmas) ni `/public/p/{key}` (vitrina pública, solo datos públicos).
- Sin dependencias nuevas (no usa el plugin de autenticación de Ktor; son ~40 líneas puras, probadas con `DashboardAuthTest`).
- **Úsalo detrás de HTTPS:** Basic envía la clave en cada petición (codificada, no cifrada).

### Cambio 18 — Cifras absurdas no reservan (pipeline)
**Problema:** el intérprete toma como oferta la primera cifra > 1000 del mensaje. Un cliente que escribía su teléfono o cédula ("mi cel 3001112233") producía una "oferta" de miles de millones, que el motor aceptaba al precio publicado (≥ PP) y **reservaba una unidad por error**.

**Ahora:** si la cifra supera 10 veces el precio publicado, el bot responde "No estoy seguro de haber entendido la cifra…" y no reserva. Constante `MAX_OFFER_FACTOR = 10`.

### Cambio 19 — Mensaje correcto ante un "comprobante" sin pago pendiente (pipeline)
El intérprete marca como comprobante cualquier mensaje con "nequi", "transferencia" o "comprobante" (incluso una simple pregunta). El pipeline siempre escalaba a humano diciendo "Recibimos tu comprobante", aunque no hubiera reserva ni archivo.

**Ahora:** **sigue escalando siempre** (decisión deliberada: hay dinero de por medio, y un cliente puede pagar *después* de que su reserva de 15 min venció). Pero el texto solo dice "Recibimos tu comprobante" si había un pago pendiente; si no, dice "Te comunico con un asesor para ayudarte con el pago". Solo extiende la reserva a 12 h cuando de verdad había una. Pruebas nuevas: sin pago pendiente, y pago tardío con reserva vencida.

### Cambio 20 — Intérprete: `110 mil`, `110k`, `110,000`
Es la forma habitual de ofertar y no se detectaba (el 110 se descartaba por ser ≤ 1000). Ahora `GeminiLlmAdapter` convierte `<1–3 dígitos> mil|k` a miles antes de buscar la cifra y acepta la coma como separador de miles. Siguen sin tomarse como precio: códigos de producto, "mil gracias", "2 kilos". (Sigue siendo el intérprete por reglas locales, **no** llama a Gemini.) Pruebas nuevas: 3.

### Cambio 21 — Acceso concurrente a `AppStore`
Desde v3 hay varias corrutinas tocando `AppStore` a la vez. Ahora el tablero toma copias de productos e inventario bajo candado; la subida de Excel, el webhook de Shopify y la vitrina pública leen/modifican bajo el mismo candado que usa `AppStoreCatalog`. El simulador ya no falla si no hay productos (antes lanzaba `NoSuchElementException`). Esto cierra el punto 7 del §4 de v3.

### Cambio 22 — `Money.formatFormattedCop()` con `Locale.US`
Usaba el idioma por defecto del equipo; con otros idiomas el separador de miles podía salir distinto. Ahora siempre produce `$120.000`.

### Cambio 23 — `run_tests.py` ya no inventa resultados
**Problema:** el script **no ejecutaba nada**: imprimía una lista fija de "✓" y "TODOS LOS TEST PASARON EXITOSAMENTE" siempre, además con una ruta fija (`/home/nehreah/Tienda`). El manual v1 incluso lo recomendaba para verificar.
**Ahora:** ejecuta `./gradlew test` (o la tarea que le pases, p. ej. `python run_tests.py :app:test`) y devuelve el código de salida real de Gradle.

### Cambio 24 — `local.properties.example`
`start.sh` copia ese archivo tal cual a `local.properties`. Traía `PAYMENT_INSTRUCTIONS=Paga por Nequi al 300 000 0000 …`: de no editarlo, **los clientes habrían recibido datos de pago falsos**. Ahora esa línea está comentada (sin ella el bot dice "Un asesor te enviará los datos de pago"). Se añadieron `DASHBOARD_USER` / `DASHBOARD_PASSWORD`.

### Pruebas nuevas (10)
`InboundMessagePipelineTest` +3 (cifra absurda, comprobante sin pago pendiente, pago tardío) · `GeminiLlmAdapterTest` +3 (`mil`/`k`, coma, falsos positivos) · `DashboardAuthTest` 3 · `DashboardRendererTest` 1. Total esperado en `:app:test`: **11 del pipeline + 6 del intérprete + 3 de autenticación + 1 del tablero**.

---

## 4. Decisiones y límites que debes conocer

1. **Decisión pendiente tuya, la más importante para el negocio — el piso de precio se filtra.** Ante una oferta por debajo del PM, `NegotiationEngine` responde "nuestro mejor precio es X" donde **X = el PM exacto**. Con una sola oferta baja el cliente descubre tu precio mínimo y ofrece justo ese. Es el diseño del plan (L4 = PM) y sus pruebas lo exigen, por eso **no lo cambié**. Alternativas: escalera gradual (primero L1, luego L2…), o contraoferta sin revelar el piso. Es un cambio en el motor y en `NegotiationEngineTest`; dime y lo hago.
2. **Todo sigue en memoria** (conversaciones, reservas, idempotencia si no hay BD). Se pierde al reiniciar.
3. **El bot no cobra ni confirma pagos.** Solo reserva y escala. Registrar la venta sigue siendo manual.
4. **Escalar silencia al bot 24 h.** Incluye el caso de un cliente que solo pregunta "¿aceptan Nequi?". Es seguro pero poco amable; mejorarlo requiere un intérprete real (Gemini), no reglas por palabras.
5. **El bot calla mientras hay escalación, pero no sabe cuándo el asesor termina.** Se reactiva solo tras 24 h sin actividad del cliente. Las respuestas del asesor (hechas desde la app de WhatsApp Business) no pasan por el sistema.
6. **Orden de mensajes:** dos mensajes casi simultáneos del mismo cliente pueden procesarse en distinto orden al de llegada.
7. **Sin outbox:** si el envío a Meta falla (token vencido, ventana de 24 h de WhatsApp…) la respuesta se pierde y solo queda el aviso en consola. Con credenciales demo los envíos reales fallan; es normal.
8. **CORS `anyHost` sigue activo.** Con contraseña definida no es explotable con credenciales, pero conviene restringirlo al dominio real cuando lo publiques.
9. **`META_APP_SECRET` vacío sigue aceptando webhooks sin firma** (solo con advertencia). Sigue siendo decisión tuya; para producción debería ser obligatorio.
10. **Credenciales demo por defecto** en `AppConfig` (Shopify, Meta, WhatsApp) siguen ahí; decisión tuya.
11. Detalle menor heredado: en `/api/excel/export`, si la BD está conectada pero la consulta falla, se exporta una lista vacía en lugar de usar los datos en memoria.

---

## 5. Lo que falta (actualizado)

1. **Compilar y correr todas las pruebas** (prioridad 1).
2. **Definir `DASHBOARD_PASSWORD`, `PAYMENT_INSTRUCTIONS` y `META_APP_SECRET`** en tu `local.properties` real antes de recibir clientes.
3. **Decidir sobre la filtración del PM** (§4.1).
4. **Persistir conversaciones y reservas** en PostgreSQL (`MsgConversationsTable` / `ComReservationsTable` existen pero no se crean ni se usan; requiere SQL de migración).
5. **Outbox funcional** para reintentar envíos fallidos (`ComOutboxTable` sin productor/procesador).
6. **Registrar la venta tras verificar el pago** (ya hay autenticación opcional para protegerlo): `recordSale`, `AntiOversellSaga`, `ProductSoldEvent`, estado `PAID_CONFIRMED`.
7. **`MetaShopAdapter` real** (publicar/actualizar/stock/despublicar; solo datos públicos).
8. **Intérprete real con Gemini** (hoy es regex) con validación estricta de la respuesta.
9. **Health-checks reales** para pasar integraciones de `DEGRADADO` a `FUNCIONAL`.
10. **Pedidos mínimos** (reservado → pagado → enviado → entregado).
11. **Identidad unificada del cliente** entre canales.
12. **Quitar credenciales demo por defecto** de `AppConfig`.
13. **Decisión abierta:** ¿rechazar `/webhooks/meta` cuando `META_APP_SECRET` está vacío?
14. **Datos de muestra del dashboard** (`PROD-001/002`, `channelMappings` y 5 `chatDeals` ficticios) siguen sembrados; conviven con los tratos reales.
15. **Restringir CORS** al dominio real.

---

## 6. Cómo probar
```bash
./gradlew test                  # todo
./gradlew :app:test             # pipeline, intérprete, autenticación y tablero
./gradlew :messaging:test
python run_tests.py             # lo mismo que ./gradlew test, con código de salida real
```

Prueba manual de la contraseña (con `DASHBOARD_PASSWORD=prueba123` en `local.properties`):
```bash
curl -i localhost:8080/                       # 401 Unauthorized
curl -i -u admin:prueba123 localhost:8080/    # 200 OK
```

Prueba manual de la cifra absurda (sin `META_APP_SECRET`, con la app corriendo): enviar al webhook un mensaje `"PROD-001, mi cel 3001112233"` → el tablero debe mostrar "En consulta / negociación" y **no** reservar (el stock vivo de PROD-001 no baja). Con `"PROD-001 te ofrezco 110 mil"` (cifra razonable) → "Reservado, esperando pago".
Ejemplo de envío:
```bash
curl -i -X POST localhost:8080/webhooks/meta -H "Content-Type: application/json" -d '{
  "object":"whatsapp_business_account",
  "entry":[{"changes":[{"value":{"messages":[
    {"from":"573001112233","id":"wamid.TEST2","text":{"body":"PROD-001 te ofrezco 110 mil"}}
  ]}}]}]
}'
```
(Repite con otro `id` cada vez: el mismo `id` se ignora por idempotencia.)
