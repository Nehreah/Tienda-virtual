# Plan complementario: catálogo existente, ciclo de vida de artículos, canales y clientes

**Proyecto:** Tienda Virtual Multicanal (Tienda-virtual-main v5.3.0)
**Fecha:** 6 de octubre de 2026 · **Versión:** 1.0
**Relación con el plan anterior:** este documento **no repite** nada de F0 a F7. Solo cubre hallazgos nuevos. Las fases se nombran **C0 a C6** para no chocar con las F.

> **Alcance de la evidencia.** Todo sale de leer el código, no de ejecutarlo. Los puntos que dependen de datos reales están marcados como **[verificar]** y la sección 2 trae consultas SQL para confirmarlos en 30 minutos antes de programar.

---

## 1. Hallazgos nuevos

| ID | Hallazgo | Evidencia | Riesgo | Fase |
|----|----------|-----------|--------|------|
| N1 | La importación de Excel actualiza la memoria **dentro del ciclo**, antes de confirmar en la base de datos. Si falla a mitad, la memoria queda con productos que la base no tiene hasta reiniciar. | Ruta `/api/excel/upload` en `Application.kt` | Alto | C1 |
| N2 | Un producto **ya vendido** que aparezca en el Excel con otro estado hace fallar **toda** la importación, con un mensaje poco claro. No hay vista previa de qué cambiará. | `ExposedInventarioRepository.upsert` | Medio | C1 |
| N3 | **No existe borrar ni archivar** desde la aplicación. Lo único es poner `Estado = Inactivo` y volver a subir el Excel. Tampoco hay alta ni edición de un artículo suelto. | Rutas de `Application.kt` (solo upload, export, sim, confirmar-pago) | Medio | C2 |
| N4 | El sistema **no puede leer** el catálogo que ya existe en Shopify o Meta. El adaptador solo publica, actualiza, ajusta stock, despublica y consulta estado. | `CatalogPublishingPort`, `ShopifyCatalogAdapter` | Alto | C3 |
| N5 | **Nada publica a los canales.** `publishProduct` no tiene llamadores en `app` ni `infra`, y `enqueueStockChange` tampoco. | Búsqueda en `app/src/main` e `infra/src/main` | Alto | C4 |
| N6 | El mapa producto → ID externo (`channelMappings`) **se lee pero nunca se llena**. La saga anti-sobreventa recibe una lista vacía y no retira nada de otros canales. | `AppStore.channelMappings` | Alto | C4 |
| N7 | Hay **dos tablas** para lo mismo: `tienda.publicacion_canal` (V007, completa: IDs, estado, precio por canal) y `tienda.publicaciones` (V010, la única que usa el código). La primera nunca se llena. La función SQL de venta encola eventos de stock a partir de las publicaciones registradas, así que si esa tabla está vacía **no se encola nada** **[verificar]**. | V007, V010, V011, `OutboxWorker` | Alto | C0, C4 |
| N8 | Un pedido de Shopify con **SKU vacío se ignora en silencio**, y uno con SKU desconocido no descuenta nada ni avisa. No hay cola de "eventos sin resolver". | `ShopifyWebhookHandler`, ruta `/webhooks/shopify` | Alto | C5 |
| N9 | Solo se suscriben pedidos creados/cancelados e `inventory/update`. Este último **solo se registra**, y además trae un `inventory_item_id` que el sistema **no puede traducir a SKU** (falta guardar ese ID). Borrar o editar un producto en Shopify pasa desapercibido. | `processInventoryUpdated` | Medio | C5 |
| N10 | `tienda.cliente` solo se llenó una vez, en la migración V008. El código que guarda conversaciones nuevas no inserta ahí **[verificar]**. No hay perfil mínimo, consentimiento ni historial de compras por cliente. | `ExposedConversationStore`, V008 | Medio | C6 |
| N11 | La tabla `publicacion_canal` tiene `ON DELETE CASCADE` hacia `inventario`: **borrar un artículo de la base elimina sus enlaces con los canales** sin dejar rastro. | V007 | Medio | C2 |

---

## 2. Verificación previa (30 minutos, antes de empezar)

Ejecútalas en Supabase (o en tu PostgreSQL). Los resultados deciden el alcance real de C0, C4 y C6.

```sql
-- N7: ¿alguna de las dos tablas de publicación tiene datos?
SELECT 'publicaciones'      AS tabla, count(*) FROM tienda.publicaciones
UNION ALL
SELECT 'publicacion_canal'  AS tabla, count(*) FROM tienda.publicacion_canal;

-- N5/N7: ¿hay eventos pendientes o históricos en la cola de salida?
SELECT count(*) AS eventos_outbox FROM tienda.outbox;

-- N10: clientes registrados vs. clientes distintos que han escrito
SELECT (SELECT count(*) FROM tienda.cliente) AS en_tabla_cliente,
       (SELECT count(*) FROM (SELECT DISTINCT channel, customer_id
                              FROM tienda.conversaciones) c) AS contactos_reales;

-- Catálogo actual: ¿cuántos productos y en qué estado?
SELECT estado, count(*) FROM tienda.inventario GROUP BY estado ORDER BY 2 DESC;
```

**Cómo leer los resultados**

- `publicaciones = 0` y `publicacion_canal = 0` → confirma N5, N6 y N7: hoy ningún producto está enlazado con un canal.
- `contactos_reales` mayor que `en_tabla_cliente` → confirma N10.
- Anota también, fuera del código: **cuántos productos tienes hoy en Shopify y en el catálogo de Meta, y si todos tienen SKU.** Define el esfuerzo de C3.

---

## 3. Principios de diseño para esta ampliación

1. **La matriz (Excel/base de datos) sigue siendo la fuente de verdad comercial.** Precio publicado, precio mínimo y costo nunca se leen desde un canal ni se escriben hacia él (el precio mínimo y el costo jamás salen del sistema; se conservan las pruebas de fuga existentes).
2. **Lo que viene de los canales es solo referencia.** La reconciliación propone; una persona confirma. Nada se crea ni se enlaza solo.
3. **Primero lectura, después escritura.** Se construye y se prueba la lectura de canales antes de permitir que el sistema escriba en ellos, con un interruptor de seguridad.
4. **Archivar antes que borrar.** El borrado definitivo existe, pero solo para artículos sin historia.
5. **Todo cambio es auditable** (quién, cuándo, desde dónde) usando el mecanismo de historial que ya existe.
6. **Sin lógica nueva en rutas ni en funciones SQL.** Casos de uso en la capa de aplicación, puertos pequeños y SQL estándar (alineado con la dirección del plan anterior).

---

## 4. Fases

### Resumen

| Fase | Objetivo | Esfuerzo | Depende de |
|------|----------|----------|------------|
| C0 | Base de datos coherente para publicaciones | 1,5 días | Verificación (sección 2) |
| C1 | Importación de Excel segura y con vista previa | 3 días | C0 |
| C2 | Ciclo de vida del artículo: crear, editar, archivar, eliminar | 4 días | C1 |
| C3 | Reconciliación del catálogo existente (**solo lectura**) | 5 días | C0 |
| C4 | Publicación y sincronización hacia los canales (**escritura**) | 6 días | C2, C3 |
| C5 | Webhooks robustos y detección de deriva de stock | 3 días | C3 |
| C6 | Clientes mínimos, consentimiento y privacidad | 3 días | Independiente |

Total: unos **25,5 días** (5 a 6 semanas a tiempo parcial). Orden recomendado: **C0 → C1 → C2 → C3 → C4 → C5**, con C6 en paralelo cuando haya hueco.

> Estas fases se apoyan en la capa de aplicación del plan anterior (F2) y en el repositorio de reservas (F1). Si aún no llegas ahí, impleméntalas igual como casos de uso en `application/`; cuando esa capa exista, solo se mueven de carpeta.

---

### C0 · Base de datos coherente para publicaciones (1,5 días)

**Objetivo.** Una sola tabla de enlaces producto ↔ canal, completa y sin pérdida de datos por borrados en cascada.

**Tareas**

1. Elegir `tienda.publicacion_canal` como tabla definitiva: guarda `externo_id`, `externo_inventory_id` (necesario para `setStock` y para resolver `inventory/update`), `precio_canal`, `estado_publicacion` y `stock_sincronizado`.
2. Migración nueva (siguiente versión libre de Flyway): copiar los datos de `publicaciones` a `publicacion_canal`, repuntar `OutboxWorker` y dejar `publicaciones` como obsoleta (no borrarla en esta fase).
3. Cambiar la clave foránea a `ON DELETE RESTRICT` para que borrar un artículo con enlaces falle en lugar de borrar los enlaces en silencio (N11).
4. Agregar a `inventario` las columnas `archivado_en`, `archivado_por` y `motivo_archivo` (nulas por defecto).
5. Crear la tabla `eventos_sin_resolver` (se usa en C5):

```sql
CREATE TABLE tienda.eventos_sin_resolver (
    id            BIGSERIAL PRIMARY KEY,
    canal         TEXT        NOT NULL,
    tipo          TEXT        NOT NULL,        -- orders/create, orders/cancelled, ...
    referencia    TEXT,                        -- id de pedido en el canal
    sku           TEXT,
    motivo        TEXT        NOT NULL,        -- SKU_VACIO, SKU_DESCONOCIDO, ...
    payload       JSONB       NOT NULL,
    estado        TEXT        NOT NULL DEFAULT 'PENDIENTE',  -- PENDIENTE, RESUELTO, DESCARTADO
    creado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resuelto_en   TIMESTAMPTZ
);
CREATE INDEX idx_esr_estado ON tienda.eventos_sin_resolver (estado, creado_en);
```

**Criterios de aceptación**

- Solo existe una tabla de publicaciones en uso y las pruebas del outbox pasan.
- Borrar un artículo con enlaces a canales es rechazado por la base de datos.

---

### C1 · Importación de Excel segura y con vista previa (3 días)

**Objetivo.** Que subir el Excel nunca deje la memoria y la base en estados distintos, y que se sepa qué cambiará antes de aplicarlo.

**Tareas**

1. **Corregir N1:** primero se aplica todo en la base de datos en una sola transacción; solo si confirma se actualiza la memoria. (Cuando el estado global desaparezca en F2, este paso deja de existir.)
2. **Vista previa** (`POST /api/excel/preview`): lee el Excel, no escribe nada y devuelve un informe por categoría:

   | Categoría | Significado |
   |-----------|-------------|
   | Creados | `Key` nueva |
   | Actualizados | `Key` existente con campos distintos (se listan los campos) |
   | Sin cambios | Idénticos |
   | No tocados | Están en la base pero no en el Excel (se informa, no se modifican) |
   | Bloqueados | Vendidos con otro estado en el Excel (N2): se explican en lugar de abortar a ciegas |

3. **Confirmación en dos pasos:** la vista previa devuelve un identificador ligado al hash del archivo; `POST /api/excel/confirmar` solo acepta ese identificador (evita aplicar un archivo distinto al revisado).
4. Registrar cada importación en la tabla `importaciones` que ya existe (usuario, fecha, hash, estado) y rechazar la misma importación repetida.
5. Mantener la regla actual de cancelar todo ante errores de formato; los bloqueados se resuelven antes de confirmar.
6. Extraer las validaciones del parser (precio mínimo ≤ precio publicado, montos positivos, formato de `Key`) a un validador compartido que usarán también el formulario y la API (C2).

**Criterios de aceptación**

- Una importación que falla a mitad deja memoria y base idénticas a como estaban.
- La vista previa lista exactamente lo que luego aplica la confirmación.
- Un vendido en el Excel con otro estado aparece como "bloqueado" con el motivo, sin tumbar el resto del informe.

---

### C2 · Ciclo de vida del artículo (4 días)

**Objetivo.** Crear, editar, archivar, restaurar y eliminar artículos sin depender del Excel.

**Casos de uso (capa de aplicación)**

| Caso de uso | Regla principal |
|-------------|-----------------|
| `CrearArticulo` | Valida con el validador compartido; `Key` única; entra como `Comprado` o `Publicado`. |
| `EditarArticulo` | No permite cambiar la `Key` (es el SKU y el vínculo con los canales). Si cambian precios, deja rastro en el historial. |
| `ArchivarArticulo` | Pone estado `Inactivo`, guarda quién/cuándo/por qué y encola la despublicación en cada canal con enlace. |
| `RestaurarArticulo` | Vuelve a `Publicado` y encola la republicación (C4). |
| `EliminarArticulo` | Solo si **no** hay ventas, reservas ni conversaciones que lo referencien. Si las hay, se ofrece archivar. |

**Reglas de bloqueo al archivar**

- Reserva **activa** sin pago: se pregunta si liberarla (libera el stock) o esperar.
- Reserva con **pago confirmado**: se bloquea hasta completar la entrega.
- Estado `Vendido`: no se archiva ni elimina (estado final).

**Endpoints y pantalla** (todos con autenticación, defensa CSRF y auditoría con usuario y origen `dashboard`):

| Método y ruta | Acción |
|---------------|--------|
| `POST /api/articulos` | Crear |
| `POST /api/articulos/{key}` | Editar |
| `POST /api/articulos/{key}/archivar` | Archivar (con motivo) |
| `POST /api/articulos/{key}/restaurar` | Restaurar |
| `POST /api/articulos/{key}/eliminar` | Eliminar definitivo (con confirmación escribiendo la `Key`) |

En el tablero: botón "Nuevo artículo" con formulario, y acciones "Editar", "Archivar" y "Eliminar" en cada fila, con diálogo de confirmación que explica las consecuencias en lenguaje claro.

**Criterios de aceptación**

- Archivar un artículo con reserva activa libera el stock o se bloquea según lo elegido, nunca queda a medias.
- Eliminar un artículo con historia es imposible; con un artículo recién creado funciona.
- El historial muestra cada acción con usuario y motivo.
- Prueba de concurrencia: archivar mientras otro cliente intenta reservar termina siempre en un estado consistente.

---

### C3 · Reconciliación del catálogo existente, solo lectura (5 días)

**Objetivo.** Descubrir qué productos ya existen en Shopify y Meta y cruzarlos con tu matriz, **sin modificar nada** en los canales.

**Tareas**

1. **Puerto nuevo y pequeño** (no se mezcla con el de publicación):

```kotlin
interface CatalogReadPort {
    val channelName: String
    suspend fun listListings(cursor: String? = null): PortResult<ListingPage>
}

data class ChannelListing(
    val externalId: String,
    val inventoryItemId: String?,   // Shopify: necesario para stock y webhooks
    val sku: String?,
    val title: String,
    val priceCop: Long?,
    val stock: Int?,
    val status: String
)
data class ListingPage(val items: List<ChannelListing>, val nextCursor: String?)
```

2. **Adaptador Shopify:** consulta GraphQL paginada de productos con sus variantes (SKU, identificador, `inventoryItem`, precio, cantidad). Respetar el límite de costo de consultas y devolver los `userErrors` en lugar de ignorarlos.
3. **Adaptador Meta:** lectura paginada de los ítems del catálogo.
4. **Caso de uso `ReconciliarCatalogo`** que cruza cada listado del canal con la matriz y clasifica:

   | Resultado | Qué significa | Acción propuesta |
   |-----------|---------------|------------------|
   | `COINCIDE` | SKU del canal = `Key` de la matriz | Enlazar (guardar IDs externos) |
   | `SOLO_EN_CANAL` | Existe en el canal, no en la matriz | Generar borrador para completar |
   | `SOLO_EN_MATRIZ` | Existe en la matriz, no en el canal | Candidato a publicar (C4) |
   | `SKU_VACIO` | El listado no tiene SKU | Resolución manual |
   | `SKU_DUPLICADO` | Mismo SKU en varios listados | Resolución manual |
   | `SKU_PARECIDO` | Difiere en mayúsculas, espacios o ceros a la izquierda | Proponer alias para confirmar |
   | `DIFERENCIA` | Coincide pero precio o stock distinto | Informar; la matriz manda |

5. **Informe descargable en Excel** (reutilizando `ExcelGenerator`), una hoja por categoría. En `SOLO_EN_CANAL` se pre-llena el precio publicado del canal y se **deja en blanco el precio mínimo y el costo**, que solo tú conoces.
6. **Pantalla "Conciliación"** en el tablero con el resumen y los botones de aplicar por lote.
7. **Aplicar** (`POST /api/conciliacion/aplicar`): únicamente enlaza (`publicacion_canal`) y crea borradores aprobados por una persona. Nunca toca precios ni costos de la matriz, y nunca escribe en el canal. Cada lote queda auditado.

**Criterios de aceptación**

- Con el interruptor de escritura apagado, la conciliación no hace ninguna llamada que modifique Shopify ni Meta (verificado con un canal falso en pruebas).
- Un producto de Shopify cuyo SKU coincide con una `Key` queda enlazado con su `externo_id` y `externo_inventory_id`.
- Los productos sin SKU o con SKU repetido aparecen listados y no se enlazan automáticamente.

**Procedimiento de puesta en marcha con tus productos actuales**

1. Congelar cambios manuales en Shopify durante la conciliación (media jornada).
2. Ejecutar la conciliación y descargar el informe.
3. Completar en Excel los productos `SOLO_EN_CANAL` (precio mínimo y costo) y corregir SKU vacíos o repetidos **en Shopify**.
4. Subir el Excel (con vista previa de C1) y volver a conciliar hasta que `COINCIDE` cubra lo que quieres gestionar.
5. Aplicar el enlace por lotes pequeños y verificar uno a uno.
6. Hacer un pedido de prueba en Shopify y comprobar que descuenta stock (ver C5).

---

### C4 · Publicación y sincronización hacia los canales, escritura (6 días)

**Objetivo.** Que lo que ocurre en la matriz (crear, editar, archivar, vender) se refleje en los canales, de forma segura, idempotente y reversible.

**Tareas**

1. **Interruptor global** `CHANNEL_WRITE_ENABLED` (por defecto `false`) y por canal (`SHOPIFY_WRITE_ENABLED`, `META_WRITE_ENABLED`). Con el interruptor apagado el sistema registra lo que haría ("modo sombra") sin llamar a la API. Se recomienda una semana en modo sombra comparando con la realidad antes de encender.
2. **Encolar desde los casos de uso, no desde SQL:** `CrearArticulo`, `EditarArticulo`, `ArchivarArticulo`, `RestaurarArticulo` y la venta encolan eventos `publish`, `update`, `unpublish` y `stock_changed` en el outbox (ampliar el campo `tipo`). Así el comportamiento es portable y testeable.
3. **Guardar el resultado:** tras publicar con éxito, escribir `externo_id` y `externo_inventory_id` en `publicacion_canal`; si falla, `ERROR_SYNC` con el mensaje y reintentos con espera creciente (el outbox ya soporta intentos y estado `DEAD`).
4. **Saga anti-sobreventa con datos reales:** leer los enlaces desde `publicacion_canal` (no desde un mapa en memoria) para saber qué retirar en cada canal (N6).
5. **Qué se publica por canal:** usar la hoja `Canales` del Excel (título y precio por canal, publicar sí/no). El parser ya la lee; falta conectarla **[verificar dónde se consume hoy]**.
6. **Contenido seguro:** el cuerpo enviado a un canal sale únicamente de `PublicProductDto`. Añadir pruebas que fallen si el JSON de cualquier canal contiene precio mínimo o costo.
7. **Despublicar al archivar** y **republicar al restaurar**, sin crear duplicados (se reutiliza el `externo_id` guardado).

**Criterios de aceptación**

- Con escritura apagada, el outbox registra intenciones y no hay llamadas de salida.
- Crear un artículo con "publicar en Shopify = SÍ" lo publica una sola vez y guarda sus IDs; reintentar no lo duplica.
- Una venta en WhatsApp reduce el stock en Shopify y Meta a través de los enlaces guardados.
- Archivar despublica; restaurar republica el mismo listado.
- Ninguna prueba de fuga encuentra precio mínimo o costo en payloads de canal.

---

### C5 · Webhooks robustos y detección de deriva (3 días)

**Objetivo.** Que ningún pedido se pierda en silencio y que los cambios manuales hechos en los canales se detecten.

**Tareas**

1. **Cola de eventos sin resolver (N8):** si una línea de pedido trae SKU vacío o que no corresponde a ninguna `Key`, se guarda en `eventos_sin_resolver` con el motivo y se muestra una alerta en el tablero. Se responde 200 a Shopify (evita reintentos en cadena) y el evento se puede **reprocesar** cuando se corrija el SKU o el enlace.
2. **Resolver `inventory/update` (N9):** usar `externo_inventory_id` para traducir a `Key`. Política por defecto: **detectar y alertar, no aceptar** el valor externo como verdad. Opcional y explícito: reponer el stock maestro hacia el canal mediante outbox.
3. **Nuevas suscripciones:** `products/update` y `products/delete`. Un `delete` marca el enlace como `ARCHIVADO` y alerta; **nunca** borra el artículo de la matriz. Un `update` alimenta el informe de deriva.
4. **Conciliación nocturna en modo lectura:** reutiliza el caso de uso de C3, compara y genera alertas de deriva (precio, stock, producto desaparecido, SKU nuevo sin enlazar).
5. **Alertas mínimas** en el tablero: eventos sin resolver, enlaces en `ERROR_SYNC`, deriva de stock mayor a un umbral configurable.

**Criterios de aceptación**

- Un pedido con SKU vacío aparece en "sin resolver" y puede reprocesarse tras corregirlo; el stock se descuenta una sola vez.
- Cambiar el stock a mano en Shopify genera una alerta de deriva en menos de 24 horas.
- Borrar un producto en Shopify marca el enlace como archivado y avisa; el artículo sigue en la matriz.

---

### C6 · Clientes mínimos, consentimiento y privacidad (3 días)

**Objetivo.** Tener una base de contactos útil sin guardar más datos personales de los necesarios.

**Estado actual.** Solo existe el identificador del canal (por ejemplo `whatsapp:57300…`), por decisión de minimización (D13). Los contactos anteriores a la migración V008 se cargaron una vez; los nuevos no se registran en `tienda.cliente` **[verificar con la consulta de la sección 2]**.

**Tareas**

1. **Registrar el contacto** al primer mensaje (caso de uso `RegistrarContacto`, invocado desde el procesamiento de mensajes): inserta o actualiza `cliente` con primer y último contacto.
2. **Ampliar `cliente` de forma mínima:**

```sql
ALTER TABLE tienda.cliente
    ADD COLUMN primer_contacto        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN ultimo_contacto        TIMESTAMPTZ,
    ADD COLUMN consentimiento_en      TIMESTAMPTZ,   -- aceptó la política de privacidad
    ADD COLUMN no_contactar           BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN notas_internas         TEXT;          -- solo visibles para asesores
```

3. **Consentimiento:** registrar la fecha en que el cliente aceptó la política (ya existe la plantilla `bienvenida_privacidad` y `POLITICA_PRIVACIDAD.md`). Respetar `no_contactar` antes de cualquier envío proactivo.
4. **Historial de compras por cliente:** enlazar las ventas con el cliente a través de la conversación (la función `registrar_venta_chat` ya parte de la conversación) y mostrar en el tablero la lista de compras de cada contacto.
5. **Contactos anteriores:** en general las APIs de mensajería no entregan el historial previo, así que lo anterior solo puede entrar por **carga propia en CSV** (identificador de canal y fecha de consentimiento), nunca sin consentimiento.
6. **Retención y derecho de supresión:** política de retención de mensajes (por ejemplo, depurar el texto de conversaciones cerradas pasado un plazo configurable) y caso de uso `EliminarDatosCliente` que anonimiza el contacto y sus mensajes conservando los registros contables de ventas.

**Criterios de aceptación**

- Un contacto nuevo aparece en `cliente` tras su primer mensaje.
- Un cliente con `no_contactar` no recibe mensajes proactivos.
- `EliminarDatosCliente` deja ventas intactas y sin datos personales asociados.

---

## 5. Transversal

**Seguridad.** Todas las rutas nuevas pasan por autenticación, límite de intentos y verificación de origen (CSRF) como las actuales. Los textos de formularios se sanean antes de mostrarse en el tablero. Los informes descargables no incluyen precio mínimo ni costo salvo el Excel de uso interno ya existente.

**Pruebas.**
- Canal falso (`FakeChannel`) que implementa los dos puertos para probar conciliación, publicación y webhooks sin red.
- Pruebas de contrato del `CatalogReadPort` y del puerto de publicación.
- Pruebas de fuga ampliadas a los nuevos payloads.
- Pruebas de concurrencia: archivar contra reservar; dos importaciones simultáneas.
- Archivos de ejemplo ("golden files") para la conciliación: SKU vacío, duplicado, parecido, solo en canal, solo en matriz.

**Observabilidad.** Métricas y alertas de: eventos sin resolver, enlaces en `ERROR_SYNC`, cola de outbox creciente, deriva de stock y fallos de publicación por canal.

**Entrega por fase.** Igual que antes: archivos completos modificados, manual de cambios (partes exactas y motivo) y diagrama de clases actualizado en archivo separado, con etiqueta de Git por fase para poder revertir.

---

## 6. Riesgos y mitigaciones

| Riesgo | Prob. | Impacto | Mitigación |
|--------|-------|---------|------------|
| Conciliar enlaza un producto equivocado y luego se publica o retira lo que no debía | Media | Alto | Lectura primero, confirmación humana, lotes pequeños, verificación con pedido de prueba, modo sombra. |
| SKUs inconsistentes en Shopify (vacíos, repetidos, con otro formato) | Alta | Medio | Informe explícito, alias confirmados, corrección en el canal antes de enlazar. |
| Límites de la API de Shopify o Meta durante lecturas masivas | Media | Medio | Paginación, respeto del costo de consultas, reintentos con espera creciente. |
| Un cambio manual en el canal se pisa sin querer al sincronizar | Media | Medio | Política "detectar y alertar" por defecto; la reposición automática es opt-in. |
| Eliminar un artículo por error | Baja | Alto | Archivar por defecto, eliminación solo sin historia y confirmando la `Key`. |
| Datos personales conservados más de lo necesario | Media | Medio | Minimización, consentimiento registrado, retención configurable, supresión. |
| Duplicar esfuerzo con el plan anterior (F1 a F4) | Media | Bajo | Estas fases se apoyan en F1/F2 y comparten decisiones (SQL portable, casos de uso). |

---

## 7. Decisiones que necesito de ti

1. ¿Cuántos productos tienes hoy en **Shopify** y en el **catálogo de Meta**, y todos tienen SKU? (define el esfuerzo de C3).
2. Cuando un producto quede enlazado, ¿quién manda sobre el **stock**: la matriz (recomendado) o el canal?
3. ¿Quieres poder **eliminar definitivamente** artículos, o prefieres que solo se archiven?
4. ¿Qué datos de cliente necesitas realmente (solo identificador, o también nombre y notas internas)?
5. ¿Prefieres encender la escritura a canales de golpe o canal por canal tras una semana en modo sombra? (recomendado: canal por canal)

Con tus respuestas, la siguiente entrega sería **C0 y C1**: archivos completos modificados, manual de cambios y diagrama de clases actualizado.
