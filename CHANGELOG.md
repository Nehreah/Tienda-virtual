# Registro de cambios

Reemplaza a los `MANUAL_DE_CAMBIOS_vNN.md` sueltos. Lo más reciente primero.

## v15 — Sesiones y roles, errores que no se pierden, pruebas de rutas

**Estado de verificación:** revisión estática. En el entorno de trabajo no hubo compilador de Kotlin, red ni base de datos:
antes de integrar ejecuta `./gradlew check` y prueba el login en un navegador.

### Seguridad del tablero
- **Formulario de ingreso** (`/login`) con cookie de sesión firmada (HMAC-SHA256, HttpOnly, `SameSite=Strict`, `Secure` en producción,
  8 horas) y botón **Salir** (`POST /logout`). HTTP Basic sigue funcionando para `curl` y scripts.
- **Roles:** `ADMIN` y `LECTURA`. Usuario de solo lectura opcional (`DASHBOARD_VIEWER_USER` / `DASHBOARD_VIEWER_PASSWORD`):
  consulta y exporta, pero toda ruta que modifica datos responde 403.
- `SESSION_SECRET` opcional: fijo = las sesiones sobreviven a un reinicio; vacío = se cierran al reiniciar.
- El login comparte el límite de intentos fallidos con HTTP Basic, exige Origin propio, lee el cuerpo con tope de 8 KB y
  solo redirige a rutas internas (sin redirección abierta).
- Un navegador sin sesión va a `/login`; htmx recibe `HX-Redirect`; el resto recibe 401 con el desafío Basic de siempre.

### Manejo de errores (antes se perdían o se disfrazaban)
- `venderUnidades` / `devolverUnidades`: con la base caída devolvían `false`, el webhook de Shopify lo leía como "duplicado" y marcaba
  el pedido como procesado: **una venta real se descartaba**. Ahora el error sube, el webhook responde 500 y Shopify reintenta.
- `ReconcileCatalogUseCase`: un canal que fallaba se trataba como "canal vacío" y todo el catálogo salía como "solo local".
  Ahora el canal se omite y el informe lista `failedChannels`.
- Arranque en **producción**: si falla crear un repositorio, cargar el catálogo o recuperar las reservas, la app no arranca
  (antes seguía "arriba" y guardaba en memoria). En local se mantiene el modo degradado con aviso.
- Rutas de ventas, artículos y chat: ya no devuelven `e.message` crudo con 500. Datos inválidos → 400, reglas de negocio o de la base → 409,
  errores inesperados → 500 genérico con un código que también queda en el registro con su traza (`respondError`).
- `ExposedConversationStore.save` ya no guarda `[]` (borrando el historial) si falla la serialización.
- Registros añadidos: pago no confirmado, degradación de la idempotencia a memoria, estado de conversación desconocido, eventos del chat.

### Entorno
- Java **21** unificado: `build.gradle.kts` (`javaVersion`), `Dockerfile` y CI. Antes el toolchain pedía 17 y Docker/CI usaban 21.
- `start.sh` y `start.bat` comprueban que haya Java.
- `ExcelGeneratorTest` ya no escribe `NEGOCIO.xlsx` en el árbol del proyecto.

### Pruebas nuevas
- Rutas con `testApplication` (sin red ni base): acceso/login/roles/límite de intentos, socios, pagos, webhooks de Shopify y Meta
  (firma y tamaño), exportación y subida de Excel, vitrina pública (nunca costo ni mínimo) y `respondError`.
- Unitarias: `SessionManager`, roles, `safeNextPath`, canal caído en la reconciliación.

### Repositorio
- `CHANGELOG.md` reemplaza los manuales por versión; ver `scripts/limpiar_repositorio.sh` (o `.ps1`) para mover los archivos antiguos.

### Pendiente (no cubierto en esta versión)
- Estado en memoria + base de datos (`AppStore`, candados por hash): sigue siendo una sola instancia.
- `DashboardRenderer.kt` (HTML en strings).
- Pruebas de las rutas de ventas e importación de Excel con base de datos real (necesitan PostgreSQL).

## v14 — Contraoferta nunca por debajo de la oferta del cliente
En los pasos avanzados de contraoferta, `NegotiationEngine` podía ofrecer un precio más barato que el que el cliente había
propuesto. Ahora, si la contraoferta calculada es menor o igual a la oferta del cliente, se acepta la oferta del cliente (siempre
que sea ≥ PM). El precio mínimo sigue siendo un piso inviolable. Test de regresión: `NegotiationEngineCounterOfferTest`.

## v13 — Tabla de reparaciones del dashboard
- Un error HTML del panel (503 sin BD, 500 con motivo) ahora se muestra en vez de "No se pudo cargar el módulo".
- Guardar una reparación actualiza el panel en el sitio (sin salir a Inicio ni mostrar JSON).
- `dbRepository` se crea según `databaseConnected`; una sola consulta para todas las reparaciones; la clave va codificada en la URL;
  si guardar falla, la fila conserva lo escrito.

## Versiones anteriores (v6 a v12)
Resumen según el README anterior: v8 primera entrega de módulos de socios y reparaciones; v9 limitaciones y próximos pasos;
v10 matriz de inversión y validaciones. Los planes de la fase v6 están en `docs/historico/`. Para el detalle usa `git log`.
