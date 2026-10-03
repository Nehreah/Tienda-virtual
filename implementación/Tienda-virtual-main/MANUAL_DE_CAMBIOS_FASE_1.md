# Manual de cambios — Fase 1 «Cerrar el acceso: fallar cerrado por defecto»

Proyecto: Tienda Virtual Multicanal v5.3.0 · Kotlin / Ktor / Postgres
Plan de referencia: `Plan de implementación Tienda Virtual` (Fase 1, tareas 1.1 a 1.6)

> ## ⚠️ Aviso de verificación (léelo primero)
>
> En este entorno **no hay Gradle ni acceso a Maven Central**, así que **no se pudo ejecutar `./gradlew build` ni `./gradlew test`**
> (igual que en las rondas v1–v4). Para no entregar código a ciegas se hizo esto:
>
> | Verificación | Resultado |
> |---|---|
> | Compilar con `kotlinc 1.9.22` las clases nuevas/cambiadas que **no dependen de Ktor** (`AppConfig`, `DashboardAuth`, `AuthThrottle`, `OriginGuard`, `MetaWebhookReceiver`) | ✅ Compilan sin errores |
> | Ejecutar **las pruebas reales** de esas clases con un mini-ejecutor de JUnit | ✅ **44 de 44 pasan** (AppConfig 6 · DashboardAuth 10 · AuthThrottle 9 · OriginGuard 8 · MetaWebhookReceiver, parte de firma/token, 11) |
> | Comprobar que la prueba de token vacío **falla con el código original** | ✅ El original devolvía el `challenge` con token vacío; el nuevo devuelve `null` |
> | Probar `start.sh` en un directorio vacío (con un `gradlew` falso) | ✅ Genera `local.properties` con permisos 600 y claves aleatorias; no pisa uno existente; funciona sin `openssl` |
> | Validar la sintaxis Mermaid del diagrama v5 | ✅ Válido |
> | Compilar `Application.kt` | ❌ **No verificable aquí** (necesita Ktor). Se revisó línea a línea y no tiene errores de sintaxis, pero **su compilación y las rutas HTTP no se probaron** |
> | Pruebas de `MetaWebhookReceiverTest` que parsean JSON | ❌ No ejecutadas aquí (necesitan `kotlinx.serialization`); no se modificó ese código |
>
> **Lo primero que debes hacer:** `./gradlew test`. Si algo no compila (lo más probable: `Application.kt`, o el problema ya conocido de
> Gradle 9.7.1 + Kotlin 1.9.22 de la Fase 0), pega el mensaje exacto y lo corrijo.

---

## 1. Resumen

| ID | Tarea | Estado |
|---|---|---|
| 1.1 | Clave del tablero obligatoria (producción) / solo 127.0.0.1 (local) + manual de la clave | ✅ Hecho |
| 1.2 | Firma de Meta siempre obligatoria; token vacío rechazado | ✅ Hecho |
| 1.3 | CORS cerrado (sin `anyHost()`) | ✅ Hecho |
| 1.4 | Defensa CSRF — **opción mínima** (Origin/Referer) | ✅ Hecho (la opción completa con cookie + token es la tarea 4.7) |
| 1.5 | Secretos aleatorios al crear la configuración + rechazo de placeholders | ✅ Hecho en `start.sh` · ⚠️ `start.bat` pendiente (ver §5) |
| 1.6 | Límite de intentos por IP (fuerza bruta) | ✅ Hecho |

Todos los criterios de aceptación tienen una prueba automática **salvo los que dependen de HTTP real** (ver la lista de comprobación manual en §6).

---

## 2. Archivos entregados

**Nuevos (8)**

| Archivo | Para qué |
|---|---|
| `app/src/main/kotlin/com/automata/app/dashboard/AuthThrottle.kt` | Límite de intentos fallidos por IP (1.6) |
| `app/src/main/kotlin/com/automata/app/dashboard/OriginGuard.kt` | Defensa CSRF mínima (1.4) |
| `app/src/test/kotlin/com/automata/app/dashboard/AuthThrottleTest.kt` | 9 pruebas de 1.6 |
| `app/src/test/kotlin/com/automata/app/dashboard/OriginGuardTest.kt` | 8 pruebas de 1.4 |
| `infra/src/test/kotlin/com/automata/infra/config/AppConfigTest.kt` | 6 pruebas de `APP_ENV` y placeholders (1.1, 1.5) |
| `docs/MANUAL_CLAVE_TABLERO.md` | Manual de la clave (entregable de 1.1), enlazado desde el README |
| `DIAGRAMA_DE_CLASES_v5.md` | Diagrama de clases actualizado |
| `MANUAL_DE_CAMBIOS_FASE_1.md` | Este documento |

**Modificados (10)**

| Archivo | Qué cambió |
|---|---|
| `infra/.../config/AppConfig.kt` | `AppEnv`, `ConfigPlaceholders`, `APP_ENV`, `TRUST_PROXY`; `get()` ignora placeholders; se elimina `requireMetaSignature` |
| `app/.../dashboard/DashboardAuth.kt` | Se agrega `DashboardSecurity` (política de arranque). La clase `DashboardAuth` no cambia |
| `adapters-channels/.../MetaWebhookReceiver.kt` | `authenticate()` + `SignatureResult`; `verifyWebhookChallenge()` rechaza token vacío |
| `app/.../Application.kt` | Validación al arrancar, host según entorno, CORS condicional, guardia del tablero, webhook de Meta, textos del tablero |
| `start.sh` | Genera `local.properties` con claves aleatorias |
| `local.properties.example` | Documenta `APP_ENV`, `TRUST_PROXY`; quita `REQUIRE_META_SIGNATURE` |
| `README.md`, `MANUAL_DE_USO.md` | Reflejan el nuevo flujo y enlazan el manual de la clave |
| `app/src/test/.../DashboardAuthTest.kt` | +7 pruebas de la política de arranque (las 3 originales siguen) |
| `adapters-channels/src/test/.../MetaWebhookReceiverTest.kt` | +6 pruebas de firma/token (las 10 originales siguen) |

No se tocó ningún otro archivo. Los manuales `MANUAL_DE_CAMBIOS_v4.md` y `DIAGRAMA_DE_CLASES_v4.md` quedan intactos como histórico.
El **Anexo A** al final lista cada cambio de código línea por línea (diff real).

---

## 3. Qué se cambió, dónde y por qué

### 1.1 Clave del tablero obligatoria

**Problema.** Sin `DASHBOARD_PASSWORD` el tablero quedaba abierto y escuchando en `0.0.0.0`: cualquiera en la red (o en Internet) podía ver costos y precio mínimo, descargar el Excel y subir otro.

**Cambio.**
- `AppConfig.kt`: nuevo `enum AppEnv { LOCAL, PRODUCTION }` y `AppConfig.appEnv` (lee `APP_ENV`; por defecto `local`; un valor desconocido lanza error en lugar de caer en `local`).
- `DashboardAuth.kt`: nuevo `object DashboardSecurity` con `plan(env, user, password)`:
  - `production` + clave vacía, en blanco o de menos de 12 caracteres (o usuario vacío) → lanza `IllegalStateException` con mensaje claro.
  - `production` → host `0.0.0.0`.
  - `local` → host `127.0.0.1` **siempre** (con o sin clave); sin clave agrega un aviso.
- `Application.kt` → `main()`: llama a `DashboardSecurity.plan(...)` **antes** de conectar a la BD y abrir el puerto; si falla, imprime `ERROR DE CONFIGURACIÓN` y termina con código 1. El servidor se abre en `securityPlan.host` (antes, `"0.0.0.0"` fijo). `module()` vuelve a validar (por si se arranca sin `main`).
- Las 3 rutas que pide el plan ya pasaban por `requireDashboardAuth`: `/api/excel/export`, `/api/excel/upload`, `/api/sim/chat` (y `/`). Se mantienen protegidas. `/public/p/{key}` sigue público por diseño (no expone PM ni costos) y los webhooks tienen su propia firma.
- `docs/MANUAL_CLAVE_TABLERO.md` escrito y enlazado desde el README.

**Prueba.** `DashboardAuthTest`: producción sin clave / clave en blanco / 11 caracteres / sin usuario → lanza error; 12 caracteres → arranca en `0.0.0.0`; local sin clave → `127.0.0.1` + aviso.

### 1.2 Firma de Meta siempre obligatoria

**Problema.** Con `META_APP_SECRET` vacío (el valor por defecto) `POST /webhooks/meta` aceptaba cualquier cuerpo: un POST falso creaba reservas y enviaba mensajes. Además, un token de verificación vacío coincidía con un token vacío de Meta (`"" == ""`), verificado con el código original.

**Cambio.**
- `MetaWebhookReceiver.kt`: nuevo `authenticate(rawBody, header, appSecret)` → `SignatureResult { VALID, SECRET_NOT_CONFIGURED, INVALID }`. Secreto vacío → `SECRET_NOT_CONFIGURED`; firma ausente o incorrecta → `INVALID`.
- `verifyWebhookChallenge`: devuelve `null` si el token configurado **o** el recibido está vacío; compara en tiempo constante; exige `mode == "subscribe"`.
- `Application.kt`: `POST /webhooks/meta` traduce el resultado: `SECRET_NOT_CONFIGURED` → **503**, `INVALID` → **401**. Se elimina `REQUIRE_META_SIGNATURE` (de `AppConfig`, del código, del `.example` y del README). El tablero ahora dice «SIN META_APP_SECRET: el webhook responde 503».

**Prueba.** `MetaWebhookReceiverTest`: sin firma, firma mala, cuerpo alterado, secreto vacío, token vacío (de ambos lados) y modo ≠ subscribe fallan; firma buena pasa.

### 1.3 CORS cerrado

**Cambio.** `Application.kt`: `install(CORS)` solo se ejecuta si `CORS_ALLOWED_HOST` tiene valor, y entonces solo con `allowHost(...)`. Se eliminó `anyHost()`. Sin la variable no hay CORS (solo mismo origen) y la respuesta no lleva cabeceras `Access-Control-Allow-*`.

### 1.4 Defensa CSRF (opción mínima)

**Problema.** El navegador reenvía solo las credenciales Basic guardadas; una página maliciosa abierta en otra pestaña podía hacer que el navegador del administrador subiera un Excel o usara el simulador.

**Cambio.**
- Nuevo `OriginGuard.isSameOrigin(origin, referer, host)`: el `Origin` (o, si no hay, el `Referer`) debe tener el mismo host y puerto que la cabecera `Host`. Rechaza `Origin: null`, esquemas que no sean http/https, `usuario@` dentro del host, y cualquier POST sin `Origin` ni `Referer`. Puertos 80/443 equivalen a «sin puerto».
- `Application.kt`: `requireDashboardAuth(guard, mutating = true)` en `POST /api/sim/chat` y `POST /api/excel/upload` → **403** si el origen no coincide, **aunque las credenciales sean válidas**. Con `TRUST_PROXY=true` se usa `X-Forwarded-Host` en lugar de `Host`.
- Esta defensa aplica también en modo local sin clave (un sitio web cualquiera podría atacar `127.0.0.1`).

### 1.5 Secretos aleatorios y placeholders

**Problema.** `start.sh` copiaba el `.example` tal cual: verify token `verify_token_secret_123`, tokens `shpat_xxx`… conocidos por cualquiera que lea el repositorio, y la app los trataba como configurados.

**Cambio.**
- `start.sh`: ya **no** copia el `.example`. Genera `local.properties` nuevo (permisos 600) con `APP_ENV=local`, `DASHBOARD_PASSWORD` y `META_WEBHOOK_VERIFY_TOKEN` aleatorios de 32 caracteres (`openssl rand`, con respaldo a `/dev/urandom` si no hay openssl) y las integraciones comentadas. No sobrescribe un archivo existente. La clave **no se imprime** en la terminal; el mensaje indica dónde está.
- `AppConfig.kt`: nuevo `ConfigPlaceholders.isPlaceholder()` (`verify_token_secret_123`, `change_me`, `cambiar_esta_clave`, `shpat_xxx…`, `shpss_xxx…`, `EAAGxxx…`, `AIzaSyxxx…`, 5+ equis seguidas, `ID_DEL_PROYECTO`, `aws-0-REGION`, `3XX XXX XXXX`, valores entre `< >`…). `AppConfig.get()` trata un placeholder como **vacío** y avisa por consola (solo el nombre de la variable, nunca el valor). Así `configured(...)` da falso y la integración aparece **DESHABILITADO** en el tablero con el texto «hay valores de ejemplo (placeholders) sin reemplazar» (`AppConfig.hasPlaceholder`).
- `local.properties.example` sigue con sus ejemplos (es documentación) pero lo explica en la cabecera.

**Prueba.** `AppConfigTest`: todos los valores del `.example` se detectan como placeholder; secretos de apariencia real no. `start.sh` verificado: ninguna línea activa del archivo generado coincide con el `.example`.

### 1.6 Fuerza bruta

**Cambio.** Nuevo `AuthThrottle`: 5 intentos fallidos en 5 minutos bloquean la IP; el siguiente intento recibe **429** con `Retry-After` **aunque la clave sea correcta**. La espera es de 1 min y se duplica en cada reincidencia (2, 4, 8… tope 1 h); tras más de 1 h sin actividad se olvida. Memoria acotada a 10 000 clientes. Reloj inyectable para pruebas.
- `Application.kt` → `requireDashboardAuth`: consulta el bloqueo antes de validar; cuenta un fallo **solo si la petición traía** cabecera `Authorization` incorrecta (el primer desafío del navegador, sin cabecera, no cuenta).
- IP del cliente: `request.local.remoteHost`; con `TRUST_PROXY=true`, la última entrada de `X-Forwarded-For` (la que agrega tu proxy).

---

## 4. Decisiones de diseño que difieren o completan el plan

1. **En `local` el servidor SIEMPRE escucha en `127.0.0.1`, con o sin clave.** El plan lo pedía solo «sin clave». Como `start.sh` ahora genera clave por defecto, la regla literal habría dejado el modo local abierto a toda la red por HTTP (Basic en claro). Si necesitas acceder desde otro equipo o Docker, usa `APP_ENV=production` con clave de 12+ caracteres.
2. **POST sin `Origin` ni `Referer` → 403.** Es la postura «falla cerrado». Los navegadores modernos envían `Origin` en todo POST; `curl` y los scripts no lo hacen y deben agregarlo (ejemplo en el manual de la clave).
3. **No se resetean los fallos tras un login correcto.** Si el administrador entrara bien desde la misma IP que un atacante (proxy compartido), un reseteo le daría intentos ilimitados. Los fallos caducan solos a los 5 minutos.
4. **Se agrega `TRUST_PROXY` (no estaba en el plan).** Sin él, detrás de un proxy todos los clientes comparten la IP del proxy y un atacante bloquearía a todo el mundo; además el chequeo de `Host` fallaría. Por defecto es `false`.
5. **`DASHBOARD_PASSWORD` con valor de ejemplo cuenta como vacía** (consecuencia natural de 1.5): en producción la app no arranca.
6. **`DASHBOARD_USER=admin` queda comentado** en el archivo generado: el valor por defecto de la app ya es `admin` y así ningún valor activo coincide con el `.example` (criterio de 1.5).

---

## 5. Qué falta

**Dentro de la Fase 1**
- **`start.bat` (Windows)** sigue copiando el `.example`. No se tocó porque el plan solo nombra `start.sh` y no se puede probar PowerShell aquí. Es **seguro por diseño** (los placeholders se ignoran, sin clave solo escucha en 127.0.0.1) pero no genera claves aleatorias.
- **1.4 completa** (cookie `SameSite=Strict; HttpOnly; Secure` + token CSRF): el plan la deja para la tarea **4.7**.
- **Comprobación real de la fase:** correr `./gradlew test` y la lista de §6 (necesitan Gradle/HTTP).

**Bloqueante para todo lo demás**
- **Fase 0** (compilar): sin tocar. El wrapper usa Gradle 9.7.1 con Kotlin 1.9.22 (combinación dudosa) y `start.sh` conserva el bloque con la ruta `/home/nehreah/...` (tarea 0.3), que no se quitó para no salir del alcance pedido.

**Observado, sin cambiar** (queda en su fase)
- `POST /webhooks/meta` y `/webhooks/shopify` leen el cuerpo completo antes de validar la firma: sin tope de tamaño (**4.2**).
- Sin cabeceras `CSP`, `X-Frame-Options`, `X-Content-Type-Options` (**4.1**).
- `AuthThrottle` vive en memoria: se reinicia con la app y no se comparte entre varias instancias.
- `request.local.remoteHost` en Ktor 2.x puede resolver el nombre inverso de la IP; si notas lentitud en los intentos fallidos, avísame y lo cambio por la IP numérica.
- `ShopifyHmacVerifier` ya falla cerrado con secreto vacío (el HMAC con clave vacía lanza excepción y se rechaza), así que no necesita cambio de Fase 1; conviene una prueba explícita en la Fase 2/4.

**Fases 2 a 6:** no iniciadas (datos y ventas, negociación, robustez, base como fuente única, después).

---

## 6. Lista de comprobación del cierre de fase (manual, con la app corriendo)

Reemplaza `TU_CLAVE` por el valor de `DASHBOARD_PASSWORD` de `local.properties`.

| # | Qué comprobar | Comando | Resultado esperado |
|---|---|---|---|
| 1 | Producción sin clave no arranca | `APP_ENV=production ./gradlew :app:run` | Mensaje `ERROR DE CONFIGURACIÓN`, proceso termina con código 1 |
| 2 | Local solo escucha en loopback | `ss -ltn \| grep 8080` | `127.0.0.1:8080` (nunca `0.0.0.0` ni `*`) |
| 3 | Desde otra máquina no conecta | `curl -m 5 http://IP_DEL_SERVIDOR:8080/` | Conexión rechazada |
| 4 | CORS cerrado | `curl -i -H "Origin: https://otro.com" http://127.0.0.1:8080/public/p/X` | Sin cabeceras `Access-Control-Allow-*` |
| 5 | CSRF | `curl -i -u admin:TU_CLAVE -H "Origin: https://otro.com" -d "message=hola" http://127.0.0.1:8080/api/sim/chat` | `403 Origen no permitido` |
| 6 | Mismo origen sí funciona | igual que 5 con `-H "Origin: http://127.0.0.1:8080"` | `200` |
| 7 | Fuerza bruta | 6 veces: `curl -i -u admin:mala http://127.0.0.1:8080/` y luego una con la clave buena | Los 5 primeros `401`; el 6.º y la de la clave buena `429` con `Retry-After` |
| 8 | Webhook Meta sin firma | `curl -i -X POST -d '{}' http://127.0.0.1:8080/webhooks/meta` | `503` si no hay `META_APP_SECRET`; `401` si lo hay |
| 9 | Verificación con token vacío | `curl -i "http://127.0.0.1:8080/webhooks/meta?hub.mode=subscribe&hub.verify_token=&hub.challenge=abc"` | `403` |
| 10 | Placeholders | Poner `SHOPIFY_ACCESS_TOKEN=shpat_xxx` y abrir el tablero | Shopify en `DESHABILITADO` con «valores de ejemplo (placeholders) sin reemplazar» |
| 11 | Archivo generado | Borrar `local.properties` y correr `./start.sh` | Sin ningún valor del `.example`; permisos `600` |

---

## Anexo A — Diffs exactos del código modificado

### A.1 `infra/src/main/kotlin/com/automata/infra/config/AppConfig.kt`

```diff
--- infra/src/main/kotlin/com/automata/infra/config/AppConfig.kt (antes)
+++ infra/src/main/kotlin/com/automata/infra/config/AppConfig.kt (después)
@@ -2,6 +2,53 @@
 
 import java.io.File
 import java.util.Properties
+import java.util.concurrent.ConcurrentHashMap
+
+/**
+ * Entorno de ejecución (variable APP_ENV).
+ * - LOCAL (por defecto): desarrollo en tu propia máquina. El servidor escucha solo en 127.0.0.1.
+ * - PRODUCTION: servidor expuesto. Exige clave del tablero y escucha en todas las interfaces.
+ * Un valor desconocido (por ejemplo un error de tipeo como "prodution") NO se interpreta como local: la app no arranca.
+ */
+enum class AppEnv {
+    LOCAL, PRODUCTION;
+
+    companion object {
+        fun parse(raw: String?): AppEnv = when (raw?.trim()?.lowercase().orEmpty()) {
+            "", "local" -> LOCAL
+            "production" -> PRODUCTION
+            else -> throw IllegalStateException(
+                "APP_ENV='${raw?.trim()}' no es válido. Los valores permitidos son 'local' (por defecto) y 'production'."
+            )
+        }
+    }
+}
+
+/**
+ * Detecta valores de ejemplo copiados de local.properties.example (por ejemplo "verify_token_secret_123",
+ * "shpat_xxx..." o "change_me"). AppConfig los trata como si la variable estuviera vacía.
+ */
+object ConfigPlaceholders {
+    private val EXACT = setOf(
+        "change_me", "changeme", "cambiar_esta_clave", "cambiar_clave",
+        "verify_token_secret_123", "meta_cat_123456", "page_1234567890", "1001234567890",
+        "tu-tienda.myshopify.com", "postgres.id_del_proyecto"
+    )
+    private val PREFIXES = listOf("shpat_xxx", "shpss_xxx", "eaagxxx", "aizasyxxx")
+    private val FRAGMENTS = listOf("id_del_proyecto", "aws-0-region.", "tu-dominio.com", "3xx xxx xxxx")
+    // Cinco "x" seguidas no aparecen en un secreto real (hex o base62) en la práctica; en los ejemplos hay 20 o más.
+    private val RUN_OF_X = Regex("x{5,}", RegexOption.IGNORE_CASE)
+
+    fun isPlaceholder(value: String): Boolean {
+        val v = value.trim().lowercase()
+        if (v.isEmpty()) return false
+        return v in EXACT ||
+            (v.startsWith("<") && v.endsWith(">")) ||   // "<tu token aquí>"
+            PREFIXES.any { v.startsWith(it) } ||
+            FRAGMENTS.any { v.contains(it) } ||
+            RUN_OF_X.containsMatchIn(v)
+    }
+}
 
 /**
  * Carga segura de configuración y secretos.
@@ -21,16 +68,39 @@
         }
     }
 
-    fun get(key: String, defaultValue: String = ""): String {
+    private val warnedPlaceholders: MutableSet<String> = ConcurrentHashMap.newKeySet()
+
+    /** Valor tal cual está en el entorno o en local.properties ("" si no existe). La variable de entorno gana. */
+    private fun rawValue(key: String): String {
         val envVal = System.getenv(key)
         if (!envVal.isNullOrBlank()) return envVal
-
         val propVal = localProps.getProperty(key)
         if (!propVal.isNullOrBlank()) return propVal
+        return ""
+    }
 
-        return defaultValue
+    fun get(key: String, defaultValue: String = ""): String {
+        val raw = rawValue(key)
+        if (raw.isBlank()) return defaultValue
+        if (ConfigPlaceholders.isPlaceholder(raw)) {
+            // Solo se escribe el NOMBRE de la variable, nunca su valor.
+            if (warnedPlaceholders.add(key)) {
+                System.err.println("ADVERTENCIA: $key contiene un valor de ejemplo (placeholder); se ignora y esa integración queda sin configurar.")
+            }
+            return defaultValue
+        }
+        return raw
     }
 
+    /** true si la variable existe pero trae un valor de ejemplo sin reemplazar (el tablero lo explica así). */
+    fun hasPlaceholder(key: String): Boolean {
+        val raw = rawValue(key)
+        return raw.isNotBlank() && ConfigPlaceholders.isPlaceholder(raw)
+    }
+
+    /** Entorno de ejecución. Lanza IllegalStateException si APP_ENV tiene un valor desconocido. */
+    val appEnv: AppEnv get() = AppEnv.parse(get("APP_ENV"))
+
     val port: Int get() = get("PORT", "8080").toIntOrNull() ?: 8080
 
     // Base de datos (Supabase / PostgreSQL). Sin valores por defecto: si falta, la app debe fallar claro.
@@ -53,6 +123,7 @@
     // Versión de la Graph API de Meta (WhatsApp, Instagram, Messenger). Centralizada aquí para
     // poder actualizarla sin tocar los adaptadores. Verifica la vigente en developers.facebook.com/docs/graph-api/changelog
     // App Secret de la app de Meta: firma de los webhooks (X-Hub-Signature-256). Sin valor por defecto.
+    // Es OBLIGATORIO: sin él, POST /webhooks/meta responde 503 (ya no existe modo "sin firma").
     val metaAppSecret: String get() = get("META_APP_SECRET")
 
     val metaGraphApiVersion: String get() = get("META_GRAPH_API_VERSION", "v26.0")
@@ -67,17 +138,21 @@
     // Texto que el bot envía al cliente tras reservar (cómo pagar). Configúralo con tus datos reales.
     val paymentInstructions: String get() = get("PAYMENT_INSTRUCTIONS", "Un asesor te enviará los datos de pago en breve.")
 
-    // Acceso al tablero y a /api/*: HTTP Basic. Sin DASHBOARD_PASSWORD la protección queda desactivada (solo para uso local).
+    // Acceso al tablero y a /api/*: HTTP Basic.
+    // APP_ENV=production exige DASHBOARD_PASSWORD de 12+ caracteres (si no, la app no arranca).
+    // APP_ENV=local sin clave: el servidor escucha solo en 127.0.0.1. Ver docs/MANUAL_CLAVE_TABLERO.md.
     val dashboardUser: String get() = get("DASHBOARD_USER", "admin")
     val dashboardPassword: String get() = get("DASHBOARD_PASSWORD")
 
-    // Endurecimiento opcional (por defecto NO cambia nada del comportamiento actual).
-    // REQUIRE_META_SIGNATURE=true: /webhooks/meta rechaza todo si META_APP_SECRET está vacío.
-    val requireMetaSignature: Boolean get() = get("REQUIRE_META_SIGNATURE", "false").trim().equals("true", ignoreCase = true)
-
-    // CORS_ALLOWED_HOST=mi-dominio.com: solo ese origen puede llamar a la API desde un navegador (vacío = cualquiera, como hasta ahora).
+    // CORS_ALLOWED_HOST=mi-dominio.com: solo ese origen puede llamar a la API desde un navegador.
+    // Vacío = CORS NO se instala (solo mismo origen). El tablero no necesita CORS.
     val corsAllowedHost: String get() = get("CORS_ALLOWED_HOST")
 
+    // TRUST_PROXY=true: la app corre detrás de UN proxy inverso de confianza (nginx, Caddy...). Entonces la IP del cliente
+    // (límite de intentos) y el host (defensa CSRF) se leen de X-Forwarded-For / X-Forwarded-Host. Dejar en false si no hay proxy:
+    // con true y sin proxy, cualquiera podría falsear esas cabeceras.
+    val trustProxyHeaders: Boolean get() = get("TRUST_PROXY", "false").trim().equals("true", ignoreCase = true)
+
     // Google Gemini LLM API Key
     val geminiApiKey: String get() = get("GEMINI_API_KEY", "")
 }
```

### A.2 `app/src/main/kotlin/com/automata/app/dashboard/DashboardAuth.kt`

```diff
--- app/src/main/kotlin/com/automata/app/dashboard/DashboardAuth.kt (antes)
+++ app/src/main/kotlin/com/automata/app/dashboard/DashboardAuth.kt (después)
@@ -1,12 +1,14 @@
 package com.automata.app.dashboard
 
+import com.automata.infra.config.AppEnv
 import java.security.MessageDigest
 import java.util.Base64
 
-// Autenticacion HTTP Basic opcional para el tablero y las rutas /api/...
+// Autenticacion HTTP Basic para el tablero y las rutas /api/...
 //
-// - Si [password] esta vacio, la proteccion esta DESACTIVADA y todo pasa
-//   (sin contrasena; la app avisa por consola).
+// - Si [password] esta vacio, esta clase deja pasar todo. Eso SOLO ocurre en APP_ENV=local:
+//   [DashboardSecurity.plan] impide crear una instancia asi en produccion y, en local sin clave,
+//   obliga a escuchar unicamente en 127.0.0.1.
 // - Si tiene valor, cada peticion debe traer: Authorization: Basic base64(usuario:clave)
 //
 // Los webhooks y la vitrina publica NO pasan por aqui:
@@ -40,3 +42,47 @@
         return userOk && passwordOk
     }
 }
+
+/**
+ * Política de arranque del tablero: falla cerrado por defecto.
+ *
+ * - PRODUCTION: DASHBOARD_USER y DASHBOARD_PASSWORD (mínimo [MIN_PASSWORD_LENGTH] caracteres) son obligatorios;
+ *   si faltan, [plan] lanza IllegalStateException y la app no arranca. Escucha en todas las interfaces.
+ * - LOCAL: escucha SIEMPRE solo en 127.0.0.1 (nunca en 0.0.0.0), con o sin clave; sin clave se agrega un aviso.
+ */
+object DashboardSecurity {
+    const val MIN_PASSWORD_LENGTH = 12
+    const val LOOPBACK_HOST = "127.0.0.1"
+    const val ALL_INTERFACES_HOST = "0.0.0.0"
+
+    data class Plan(val host: String, val auth: DashboardAuth, val warnings: List<String>)
+
+    fun plan(env: AppEnv, user: String, password: String): Plan {
+        val warnings = mutableListOf<String>()
+
+        if (env == AppEnv.PRODUCTION) {
+            if (user.isBlank()) {
+                throw IllegalStateException(
+                    "APP_ENV=production: DASHBOARD_USER no puede estar vacío."
+                )
+            }
+            if (password.isBlank() || password.length < MIN_PASSWORD_LENGTH) {
+                throw IllegalStateException(
+                    "APP_ENV=production: DASHBOARD_PASSWORD está vacía o tiene menos de $MIN_PASSWORD_LENGTH caracteres. " +
+                        "Genera una con: openssl rand -base64 24  y guárdala en local.properties o como variable de entorno. " +
+                        "Detalles en docs/MANUAL_CLAVE_TABLERO.md."
+                )
+            }
+            return Plan(ALL_INTERFACES_HOST, DashboardAuth(user, password), warnings)
+        }
+
+        // LOCAL
+        if (password.isBlank()) {
+            warnings += "DASHBOARD_PASSWORD vacío (APP_ENV=local): el tablero no pide clave y por eso solo escucha en $LOOPBACK_HOST. " +
+                "Defínela antes de pasar a producción."
+        } else if (password.length < MIN_PASSWORD_LENGTH) {
+            warnings += "DASHBOARD_PASSWORD tiene menos de $MIN_PASSWORD_LENGTH caracteres; en APP_ENV=production la app no arrancaría así."
+        }
+        return Plan(LOOPBACK_HOST, DashboardAuth(user, password), warnings)
+    }
+}
```

### A.3 `adapters-channels/src/main/kotlin/com/automata/adapters/channels/MetaWebhookReceiver.kt`

```diff
--- adapters-channels/src/main/kotlin/com/automata/adapters/channels/MetaWebhookReceiver.kt (antes)
+++ adapters-channels/src/main/kotlin/com/automata/adapters/channels/MetaWebhookReceiver.kt (después)
@@ -19,11 +19,37 @@
         val timestamp: Long
     )
 
+    /** Resultado de autenticar un POST de Meta. Application lo traduce a 200 / 503 / 401. */
+    enum class SignatureResult {
+        VALID,
+        /** No hay META_APP_SECRET: el webhook está deshabilitado (HTTP 503). */
+        SECRET_NOT_CONFIGURED,
+        /** Falta la firma, está mal formada o no coincide (HTTP 401). */
+        INVALID
+    }
+
+    /**
+     * Verificación inicial de Meta (GET). Falla cerrado: un token vacío, de cualquiera de los dos lados,
+     * nunca se acepta (antes, token == "" coincidía con un verifyToken vacío).
+     */
     fun verifyWebhookChallenge(mode: String?, token: String?, challenge: String?): String? {
-        if (mode == "subscribe" && token == verifyToken) {
-            return challenge
-        }
-        return null
+        if (verifyToken.isBlank() || token.isNullOrBlank()) return null
+        if (mode != "subscribe") return null
+        val tokensMatch = java.security.MessageDigest.isEqual(
+            token.toByteArray(Charsets.UTF_8),
+            verifyToken.toByteArray(Charsets.UTF_8)
+        )
+        return if (tokensMatch) challenge else null
+    }
+
+    /**
+     * La firma de Meta es siempre obligatoria. Sin App Secret el webhook no funciona (503);
+     * con App Secret, toda petición sin X-Hub-Signature-256 válida se rechaza (401).
+     */
+    fun authenticate(rawBody: ByteArray, signatureHeader: String?, appSecret: String): SignatureResult = when {
+        appSecret.isBlank() -> SignatureResult.SECRET_NOT_CONFIGURED
+        verifySignature(rawBody, signatureHeader, appSecret) -> SignatureResult.VALID
+        else -> SignatureResult.INVALID
     }
 
     /**
```

### A.4 `app/src/main/kotlin/com/automata/app/Application.kt`

```diff
--- app/src/main/kotlin/com/automata/app/Application.kt (antes)
+++ app/src/main/kotlin/com/automata/app/Application.kt (después)
@@ -54,6 +54,7 @@
 import kotlinx.coroutines.isActive
 import kotlinx.coroutines.launch
 import kotlinx.coroutines.runBlocking
+import kotlin.system.exitProcess
 
 object AppStore {
     val products = mutableMapOf<String, CommercialProduct>()
@@ -76,7 +77,10 @@
         fun status(ok: Boolean) = if (ok) ApiStatus.DEGRADADO else ApiStatus.DESHABILITADO
         val noCheck = "Sin verificar"
         val metaSig = if (AppConfig.metaAppSecret.isNotBlank()) "firma de webhook activa"
-                      else "SIN META_APP_SECRET: webhook sin verificar firma"
+                      else "SIN META_APP_SECRET: el webhook responde 503 (firma obligatoria)"
+        // Si la variable existe pero trae un valor de ejemplo (placeholder), el tablero lo dice explícitamente.
+        fun missing(base: String, vararg keys: String) =
+            if (keys.any { AppConfig.hasPlaceholder(it) }) "$base — hay valores de ejemplo (placeholders) sin reemplazar" else base
 
         apiIntegrations.clear()
         apiIntegrations.addAll(
@@ -85,40 +89,45 @@
                     "Shopify GraphQL API", "E-Commerce",
                     status(configured("SHOPIFY_SHOP_DOMAIN", "SHOPIFY_ACCESS_TOKEN", "SHOPIFY_WEBHOOK_SECRET")),
                     if (configured("SHOPIFY_SHOP_DOMAIN", "SHOPIFY_ACCESS_TOKEN", "SHOPIFY_WEBHOOK_SECRET"))
-                        "Credenciales presentes • sin verificación en vivo" else "Faltan credenciales de Shopify",
+                        "Credenciales presentes • sin verificación en vivo"
+                    else missing("Faltan credenciales de Shopify", "SHOPIFY_SHOP_DOMAIN", "SHOPIFY_ACCESS_TOKEN", "SHOPIFY_WEBHOOK_SECRET"),
                     noCheck
                 ),
                 ApiIntegrationStatus(
                     "Meta Catalog API", "Catálogo",
                     ApiStatus.DEGRADADO.takeIf { configured("META_CATALOG_ID", "META_ACCESS_TOKEN") } ?: ApiStatus.DESHABILITADO,
-                    "Adaptador pendiente: aún NO publica ni actualiza productos en Meta",
+                    missing("Adaptador pendiente: aún NO publica ni actualiza productos en Meta", "META_CATALOG_ID", "META_ACCESS_TOKEN"),
                     noCheck
                 ),
                 ApiIntegrationStatus(
                     "WhatsApp Cloud API", "Mensajería",
                     status(configured("WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN")),
                     if (configured("WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN"))
-                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes" else "Faltan credenciales de WhatsApp",
+                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes"
+                    else missing("Faltan credenciales de WhatsApp", "WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN"),
                     noCheck
                 ),
                 ApiIntegrationStatus(
                     "Instagram DM API", "Mensajería",
                     status(configured("META_PAGE_ID", "META_ACCESS_TOKEN")),
                     if (configured("META_PAGE_ID", "META_ACCESS_TOKEN"))
-                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes" else "Faltan META_PAGE_ID / META_ACCESS_TOKEN",
+                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes"
+                    else missing("Faltan META_PAGE_ID / META_ACCESS_TOKEN", "META_PAGE_ID", "META_ACCESS_TOKEN"),
                     noCheck
                 ),
                 ApiIntegrationStatus(
                     "Facebook Messenger API", "Mensajería",
                     status(configured("META_PAGE_ID", "META_ACCESS_TOKEN")),
                     if (configured("META_PAGE_ID", "META_ACCESS_TOKEN"))
-                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes" else "Faltan META_PAGE_ID / META_ACCESS_TOKEN",
+                        "Envío configurado (${AppConfig.metaGraphApiVersion}) • $metaSig • recepción conectada al pipeline de mensajes"
+                    else missing("Faltan META_PAGE_ID / META_ACCESS_TOKEN", "META_PAGE_ID", "META_ACCESS_TOKEN"),
                     noCheck
                 ),
                 ApiIntegrationStatus(
                     "Gemini LLM Engine", "IA & NLP",
                     status(configured("GEMINI_API_KEY")),
-                    if (configured("GEMINI_API_KEY")) "API key presente • el intérprete actual usa reglas locales (aún no llama a Gemini)" else "Falta GEMINI_API_KEY • el flujo usa el intérprete por reglas locales",
+                    if (configured("GEMINI_API_KEY")) "API key presente • el intérprete actual usa reglas locales (aún no llama a Gemini)"
+                    else missing("Falta GEMINI_API_KEY • el flujo usa el intérprete por reglas locales", "GEMINI_API_KEY"),
                     noCheck
                 ),
                 ApiIntegrationStatus(
@@ -131,7 +140,7 @@
                     when {
                         databaseConnected -> "Supabase PostgreSQL conectada al iniciar"
                         AppConfig.databaseConfigured -> "DATABASE_URL presente pero la conexión falló"
-                        else -> "Sin DATABASE_URL: almacenamiento solo en memoria (datos no persisten)"
+                        else -> missing("Sin DATABASE_URL: almacenamiento solo en memoria (datos no persisten)", "DATABASE_URL")
                     },
                     if (databaseConnected) "Al iniciar" else noCheck
                 )
@@ -190,15 +199,77 @@
     }
 }
 
-/** Exige HTTP Basic si DASHBOARD_PASSWORD está definido. Devuelve false (ya respondió 401) cuando no hay acceso. */
-private suspend fun ApplicationCall.requireDashboardAuth(auth: DashboardAuth): Boolean {
-    if (auth.isAuthorized(request.headers[HttpHeaders.Authorization])) return true
-    response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Tienda Virtual Multicanal\", charset=\"UTF-8\"")
-    respondText("Autenticación requerida", status = HttpStatusCode.Unauthorized)
-    return false
+/** Lo que necesitan las rutas protegidas del tablero: credenciales, límite de intentos y cómo leer la IP del cliente. */
+private class DashboardGuard(
+    val auth: DashboardAuth,
+    val throttle: AuthThrottle,
+    val trustProxy: Boolean
+)
+
+/** IP del cliente. Solo con TRUST_PROXY=true se lee X-Forwarded-For (la última entrada la agrega NUESTRO proxy). */
+private fun ApplicationCall.clientAddress(trustProxy: Boolean): String {
+    if (trustProxy) {
+        val forwarded = request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()
+        if (!forwarded.isNullOrEmpty()) return forwarded
+    }
+    return request.local.remoteHost
+}
+
+/** CSRF (fase 1.4): el Origin (o Referer) del POST debe coincidir con el host propio. */
+private fun ApplicationCall.isSameOriginRequest(trustProxy: Boolean): Boolean {
+    val forwardedHost = if (trustProxy) {
+        request.headers["X-Forwarded-Host"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
+    } else null
+    val host = forwardedHost ?: request.headers[HttpHeaders.Host]
+    return OriginGuard.isSameOrigin(
+        origin = request.headers[HttpHeaders.Origin],
+        referer = request.headers[HttpHeaders.Referrer],
+        host = host
+    )
+}
+
+/**
+ * Protege una ruta del tablero. Devuelve false (ya respondió) cuando no hay acceso:
+ * 429 si el cliente está bloqueado por intentos fallidos, 401 si faltan o fallan las credenciales y,
+ * para rutas que modifican datos ([mutating] = true), 403 si el Origin no es el propio.
+ */
+private suspend fun ApplicationCall.requireDashboardAuth(guard: DashboardGuard, mutating: Boolean = false): Boolean {
+    val client = clientAddress(guard.trustProxy)
+
+    val wait = guard.throttle.retryAfterSeconds(client)
+    if (wait > 0) {
+        response.header(HttpHeaders.RetryAfter, wait.toString())
+        respondText("Demasiados intentos fallidos. Intenta de nuevo en $wait segundos.", status = HttpStatusCode.TooManyRequests)
+        return false
+    }
+
+    val authorization = request.headers[HttpHeaders.Authorization]
+    if (!guard.auth.isAuthorized(authorization)) {
+        // Sin cabecera = primer desafío del navegador: no cuenta como intento fallido.
+        if (authorization != null) guard.throttle.recordFailure(client)
+        response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Tienda Virtual Multicanal\", charset=\"UTF-8\"")
+        respondText("Autenticación requerida", status = HttpStatusCode.Unauthorized)
+        return false
+    }
+
+    if (mutating && !isSameOriginRequest(guard.trustProxy)) {
+        respondText("Origen no permitido", status = HttpStatusCode.Forbidden)
+        return false
+    }
+    return true
 }
 
 fun main() {
+    // Fase 1.1: falla cerrado. Si la configuración de seguridad no es válida, la app NO arranca (antes de abrir BD o puerto).
+    val securityPlan = try {
+        DashboardSecurity.plan(AppConfig.appEnv, AppConfig.dashboardUser, AppConfig.dashboardPassword)
+    } catch (e: IllegalStateException) {
+        System.err.println("ERROR DE CONFIGURACIÓN: la aplicación no arrancará.")
+        System.err.println("  ${e.message}")
+        exitProcess(1)
+    }
+    securityPlan.warnings.forEach { System.err.println("ADVERTENCIA: $it") }
+
     if (AppConfig.databaseConfigured) {
         println("Conectando a Supabase PostgreSQL...")
         try {
@@ -213,17 +284,20 @@
     val port = AppConfig.port
     println("===============================================================")
     println("  Tienda Virtual Multicanal, Semiautogestionada (Código Privado)")
-    println("  Servidor iniciado en http://localhost:$port")
+    println("  Servidor iniciado en http://${securityPlan.host}:$port  (APP_ENV=${AppConfig.appEnv.name.lowercase()})")
     println("  Canales Activos: Shopify GraphQL, Meta Catalog, WhatsApp, IG, Messenger")
     println("===============================================================")
-    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
+    embeddedServer(Netty, port = port, host = securityPlan.host, module = Application::module).start(wait = true)
 }
 
 fun Application.module() {
     install(ContentNegotiation) { json() }
-    install(CORS) {
-        val allowedHost = AppConfig.corsAllowedHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
-        if (allowedHost.isBlank()) anyHost() else allowHost(allowedHost, schemes = listOf("https", "http"))
+    // Fase 1.3: sin CORS_ALLOWED_HOST no se instala CORS (solo mismo origen). Ya no existe anyHost().
+    val corsHost = AppConfig.corsAllowedHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
+    if (corsHost.isNotBlank()) {
+        install(CORS) {
+            allowHost(corsHost, schemes = listOf("https", "http"))
+        }
     }
 
     val negotiationEngine = NegotiationEngine()
@@ -273,20 +347,22 @@
     // Idempotencia persistente si la base conectó; si no, memoria del proceso.
     val processedEvents: ProcessedEventStore =
         if (AppStore.databaseConnected) ExposedProcessedEventStore() else InMemoryProcessedEventStore()
+    // La firma de Meta es obligatoria: sin App Secret el webhook responde 503 (no hay modo "sin firma").
     if (AppConfig.metaAppSecret.isBlank()) {
-        if (AppConfig.requireMetaSignature) {
-            System.err.println("META_APP_SECRET vacío y REQUIRE_META_SIGNATURE=true: /webhooks/meta rechazará TODOS los eventos hasta que lo definas.")
-        } else {
-            System.err.println("ADVERTENCIA: META_APP_SECRET vacío; /webhooks/meta aceptará eventos SIN verificar firma.")
-        }
+        System.err.println("ADVERTENCIA: META_APP_SECRET vacío; POST /webhooks/meta responderá 503 hasta que lo definas.")
     }
-
-    // Protección opcional (HTTP Basic) del tablero y de /api/*. Los webhooks y la vitrina pública no pasan por aquí.
-    val dashboardAuth = DashboardAuth(AppConfig.dashboardUser, AppConfig.dashboardPassword)
-    if (!dashboardAuth.enabled) {
-        System.err.println("ADVERTENCIA: DASHBOARD_PASSWORD vacío; el tablero y /api/* están abiertos sin contraseña. Defínelo antes de exponer la app a Internet.")
+    if (AppConfig.metaWebhookVerifyToken.isBlank()) {
+        System.err.println("ADVERTENCIA: META_WEBHOOK_VERIFY_TOKEN vacío; Meta no podrá verificar GET /webhooks/meta.")
     }
 
+    // Protección (HTTP Basic) del tablero y de /api/*, con límite de intentos y defensa CSRF.
+    // Los webhooks y la vitrina pública no pasan por aquí. DashboardSecurity.plan lanza error si la política no se cumple.
+    val dashboardGuard = DashboardGuard(
+        auth = DashboardSecurity.plan(AppConfig.appEnv, AppConfig.dashboardUser, AppConfig.dashboardPassword).auth,
+        throttle = AuthThrottle(),
+        trustProxy = AppConfig.trustProxyHeaders
+    )
+
     val llmAdapter = GeminiLlmAdapter(apiKey = AppConfig.geminiApiKey)
 
     // Pipeline de mensajes entrantes (cliente -> conversación -> intención -> negociación -> reserva -> respuesta)
@@ -314,7 +390,7 @@
     routing {
         // 1. Dashboard Web Interactivo
         get("/") {
-            if (!call.requireDashboardAuth(dashboardAuth)) return@get
+            if (!call.requireDashboardAuth(dashboardGuard)) return@get
             // Copias tomadas bajo candado: el pipeline modifica productos/inventario desde otras corrutinas.
             val productList = synchronized(AppStore.products) { AppStore.products.values.toList() }
             val inventorySnapshot = synchronized(AppStore.inventory) { AppStore.inventory.toMap() }
@@ -339,7 +415,7 @@
 
         // 2. Simulador de Negociación L0-L4
         post("/api/sim/chat") {
-            if (!call.requireDashboardAuth(dashboardAuth)) return@post
+            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
             val parameters = call.receiveParameters()
             val message = parameters["message"] ?: ""
 
@@ -409,7 +485,7 @@
 
         // 3. Subida / Importación de Excel a Supabase & AppStore
         post("/api/excel/upload") {
-            if (!call.requireDashboardAuth(dashboardAuth)) return@post
+            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
             val multipart = call.receiveMultipart()
             var fileBytes: ByteArray? = null
 
@@ -466,7 +542,7 @@
 
         // 4. Exportación de Datos de Supabase a Excel (.xlsx)
         get("/api/excel/export") {
-            if (!call.requireDashboardAuth(dashboardAuth)) return@get
+            if (!call.requireDashboardAuth(dashboardGuard)) return@get
             // Datos en memoria (se usan si no hay BD o si la consulta a la BD falla, en vez de exportar una lista vacía).
             fun memoryItems(): List<InventarioItem> = synchronized(AppStore.products) { AppStore.products.values.toList() }.map { p ->
                 InventarioItem(
@@ -560,18 +636,17 @@
         post("/webhooks/meta") {
             val bodyBytes = call.receive<ByteArray>()
 
-            // Firma HMAC de Meta: si hay App Secret configurado, se exige y se rechaza lo que no cuadre.
-            val appSecret = AppConfig.metaAppSecret
-            if (appSecret.isBlank() && AppConfig.requireMetaSignature) {
-                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Webhook deshabilitado: falta META_APP_SECRET"))
-                return@post
-            }
-            if (appSecret.isNotBlank()) {
-                val signature = call.request.headers["X-Hub-Signature-256"]
-                if (!metaWebhookReceiver.verifySignature(bodyBytes, signature, appSecret)) {
+            // Firma HMAC de Meta SIEMPRE obligatoria: sin App Secret -> 503; sin firma válida -> 401.
+            when (metaWebhookReceiver.authenticate(bodyBytes, call.request.headers["X-Hub-Signature-256"], AppConfig.metaAppSecret)) {
+                MetaWebhookReceiver.SignatureResult.SECRET_NOT_CONFIGURED -> {
+                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Webhook deshabilitado: falta META_APP_SECRET"))
+                    return@post
+                }
+                MetaWebhookReceiver.SignatureResult.INVALID -> {
                     call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Firma X-Hub-Signature-256 inválida"))
                     return@post
                 }
+                MetaWebhookReceiver.SignatureResult.VALID -> Unit
             }
 
             val inboundEvents = metaWebhookReceiver.parseInboundPayload(bodyBytes.toString(Charsets.UTF_8))
```

### A.5 `start.sh`

```diff
--- start.sh (antes)
+++ start.sh (después)
@@ -14,9 +14,57 @@
 echo -e "${CYAN}   🚀 Tienda Virtual Multicanal, Semiautogestionada (Código Privado)${RESET}"
 echo -e "${CYAN}===============================================================${RESET}"
 
+# Genera un secreto aleatorio de 32 caracteres alfanuméricos (openssl si existe; si no, /dev/urandom).
+gen_secret() {
+    local value
+    if command -v openssl >/dev/null 2>&1; then
+        value="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
+    else
+        value="$(head -c 256 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
+    fi
+    if [ "${#value}" -lt 32 ]; then
+        echo "No se pudo generar un secreto aleatorio. Instala openssl y vuelve a intentar." >&2
+        exit 1
+    fi
+    printf '%s' "$value"
+}
+
 if [ ! -f "local.properties" ]; then
-    echo -e "${YELLOW}[+] Creando 'local.properties' desde 'local.properties.example'...${RESET}"
-    cp local.properties.example local.properties
+    # Ya NO se copia local.properties.example (traía valores de ejemplo conocidos por cualquiera).
+    # Se genera un archivo nuevo con claves aleatorias; las integraciones quedan vacías hasta que las completes.
+    echo -e "${YELLOW}[+] Creando 'local.properties' con claves aleatorias...${RESET}"
+    DASH_PASS="$(gen_secret)"
+    VERIFY_TOKEN="$(gen_secret)"
+    (
+        umask 077   # solo tu usuario puede leer el archivo
+        cat > local.properties <<PROPS
+# Generado por start.sh el $(date +%F). NO lo subas a git (ya esta en .gitignore).
+# Plantilla con todas las variables y su explicacion: local.properties.example
+APP_ENV=local
+# DASHBOARD_USER=admin   (por defecto es admin; descomenta para cambiarlo)
+DASHBOARD_PASSWORD=${DASH_PASS}
+META_WEBHOOK_VERIFY_TOKEN=${VERIFY_TOKEN}
+
+# Integraciones: descomenta y completa SOLO las que uses.
+# Mientras esten vacias el tablero las muestra como "sin configurar".
+# DATABASE_URL=
+# DATABASE_USER=
+# DATABASE_PASSWORD=
+# SHOPIFY_SHOP_DOMAIN=
+# SHOPIFY_ACCESS_TOKEN=
+# SHOPIFY_WEBHOOK_SECRET=
+# META_CATALOG_ID=
+# META_ACCESS_TOKEN=
+# META_APP_SECRET=
+# WHATSAPP_PHONE_NUMBER_ID=
+# WHATSAPP_ACCESS_TOKEN=
+# META_PAGE_ID=
+# GEMINI_API_KEY=
+# PAYMENT_INSTRUCTIONS=
+PROPS
+    )
+    echo -e "${GREEN}[+] Usuario del tablero: admin. La clave quedo en local.properties (DASHBOARD_PASSWORD).${RESET}"
+    echo -e "${YELLOW}[!] Antes de publicar en un servidor usa APP_ENV=production. Ver docs/MANUAL_CLAVE_TABLERO.md${RESET}"
 fi
 
 PORT=$(grep "^PORT=" local.properties | cut -d'=' -f2 || echo "8080")
@@ -26,7 +74,7 @@
 echo -e "${GREEN}[+] Abriendo puerto ${PORT}...${RESET}"
 echo -e "${CYAN}---------------------------------------------------------------${RESET}"
 echo -e "${CYAN}  El Dashboard Operacional estará disponible en:${RESET}"
-echo -e "${YELLOW}  👉 http://localhost:${PORT}${RESET}"
+echo -e "${YELLOW}  👉 http://127.0.0.1:${PORT}   (en modo local solo se puede abrir desde esta maquina)${RESET}"
 echo -e "${CYAN}---------------------------------------------------------------${RESET}"
 
 GRADLE_BIN="./gradlew"
```

### A.6 `local.properties.example`

```diff
--- local.properties.example (antes)
+++ local.properties.example (después)
@@ -1,7 +1,13 @@
 # Plantilla de configuración local — Tienda Virtual Multicanal, Semiautogestionada
-# Copia este archivo a 'local.properties' y asigna tus credenciales reales.
+# Esta plantilla es SOLO DOCUMENTACIÓN: start.sh ya no la copia; genera 'local.properties' con claves aleatorias.
+# Si la copias a mano, reemplaza TODOS los valores de ejemplo: la app los reconoce (shpat_xxx, verify_token_secret_123,
+# change_me, etc.), los ignora y muestra esa integración como "sin configurar" en el tablero.
 # NUNCA subas 'local.properties' al repositorio.
 
+# Entorno: 'local' (por defecto; el servidor solo escucha en 127.0.0.1) o 'production' (servidor expuesto; exige
+# DASHBOARD_PASSWORD de 12+ caracteres o la app NO arranca). Cualquier otro valor impide arrancar.
+APP_ENV=local
+
 PORT=8080
 # Base de datos: Supabase (PostgreSQL). Copia el valor exacto desde Supabase -> botón "Connect"
 # -> Session pooler (IPv4, puerto 5432). H2 ya no sirve: el esquema "tienda" es de PostgreSQL.
@@ -21,8 +27,9 @@
 META_WEBHOOK_VERIFY_TOKEN=verify_token_secret_123
 # Versión de la Graph API (WhatsApp, Instagram, Messenger). Cámbiala aquí cuando Meta retire la actual.
 META_GRAPH_API_VERSION=v26.0
-# App Secret de tu app de Meta (Configuración > Básica). Con él se verifica la firma de cada webhook.
-# Si lo dejas vacío, /webhooks/meta acepta eventos SIN verificar firma (solo para pruebas locales).
+# App Secret de tu app de Meta (Configuración > Básica). OBLIGATORIO: con él se verifica la firma de cada webhook.
+# Si lo dejas vacío, POST /webhooks/meta responde 503 y no procesa nada (ya no existe el modo "sin firma").
+# META_WEBHOOK_VERIFY_TOKEN también debe tener valor; genera uno con: openssl rand -base64 24
 META_APP_SECRET=
 
 # WhatsApp Business Cloud API
@@ -37,14 +44,16 @@
 # porque start.sh copia este archivo tal cual y los clientes recibirían ese texto.
 # PAYMENT_INSTRUCTIONS=Paga por Nequi al 3XX XXX XXXX a nombre de TU NOMBRE.
 
-# Acceso al tablero y a /api/* (HTTP Basic). Si DASHBOARD_PASSWORD queda vacío, el tablero queda ABIERTO
-# (solo para pruebas locales). Defínelo antes de exponer la app a Internet y úsala siempre detrás de HTTPS.
+# Acceso al tablero y a /api/* (HTTP Basic). Con APP_ENV=production la clave es OBLIGATORIA (12+ caracteres).
+# Genérala con: openssl rand -base64 24   — Más detalles en docs/MANUAL_CLAVE_TABLERO.md. Úsala siempre detrás de HTTPS.
+# Con APP_ENV=local y sin clave, el tablero solo escucha en 127.0.0.1.
 DASHBOARD_USER=admin
 DASHBOARD_PASSWORD=
 
-# Endurecimiento opcional (descomenta cuando publiques):
-# REQUIRE_META_SIGNATURE=true   -> /webhooks/meta rechaza todo si META_APP_SECRET está vacío
-# CORS_ALLOWED_HOST=tu-dominio.com -> solo ese dominio puede llamar a la API desde un navegador
+# Opcionales (descomenta cuando publiques):
+# CORS_ALLOWED_HOST=tu-dominio.com -> sin esta variable CORS no se instala (solo mismo origen). El tablero NO necesita CORS.
+# TRUST_PROXY=true -> solo si hay UN proxy inverso de confianza delante (nginx, Caddy): la IP del cliente y el host se leen
+#                     de X-Forwarded-For / X-Forwarded-Host. Sin proxy déjalo en false: se podrían falsear.
 
 # Google Gemini LLM API Key
 GEMINI_API_KEY=AIzaSyxxxxxxxxxxxxxxxxxxxxxxx
```

---

*Archivos nuevos completos: `AuthThrottle.kt`, `OriginGuard.kt` y las pruebas nuevas están íntegros en el proyecto (rutas en §2).*
