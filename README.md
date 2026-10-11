# Tienda Virtual Multicanal, Semiautogestionada

> **Código Privado y Confidencial** — Kotlin · Ktor · PostgreSQL (Supabase) · Shopify GraphQL API · Meta APIs (WhatsApp, Instagram, Messenger) · Gemini LLM

Plataforma de gestión comercial que separa la **Matriz Comercial (Excel)** —fuente de verdad de lo que se vende y a qué precios— del **Estado Operacional (PostgreSQL)** que controla stock, reservas y conversaciones en tiempo real.

El catálogo se carga subiendo un archivo `.xlsx`. Desde ese momento, el sistema atiende mensajes de clientes en WhatsApp, Instagram y Messenger de forma semiautomática: negocia precios respetando tus márgenes, reserva stock, y escala a un humano cuando el cliente dice que ya pagó. Todo se refleja en el dashboard web en tiempo real.

---

## ⚡ Inicio Rápido

### Linux / macOS
```bash
./start.sh
```

### Windows
```cmd
start.bat
```

El script crea `local.properties` a partir de la plantilla si no existe, compila y levanta el servidor:
👉 **http://localhost:8080**

---

## 🗺️ Cómo funciona

```
Tu Excel (.xlsx)
      │
      ▼  POST /api/excel/upload
  AppStore ──────────────────────────── Supabase PostgreSQL (si DATABASE_URL está configurado)
  (memoria)                                       │
      │                                           │
      ├── GET /              Dashboard web (productos, stock, integraciones, tratos)
      │
      ├── POST /api/sim/chat  Simulador de negociación (prueba el motor L0-L4 desde el navegador)
      │
      ├── GET /api/excel/export  Descarga el inventario actual como .xlsx
      │
      ├── GET /public/p/{key}   Vitrina pública por producto (JSON, sin precios privados)
      │
      ├── POST /webhooks/shopify  Recibe órdenes de Shopify → descuenta stock → retira del resto
      │
      └── GET|POST /webhooks/meta  Recibe mensajes de WhatsApp / Instagram / Messenger
                │
                ▼
         Pipeline de mensajes
         (intención → negociación L0-L4 → reserva → instrucciones de pago → escalado humano)
```

---

## 🚀 Módulos del Proyecto

```
tienda-virtual-multicanal/
├── shared-kernel/      # Money (Long en COP), ProductKey, PortResult, eventos de dominio
├── commerce/           # CommercialProduct, OperationalInventory, motor de negociación L0-L4,
│                       #   saga anti-sobreventa, cálculo financiero (ganancia, ROI, margen)
├── messaging/          # Máquina de estados de conversación, LlmPort, MessagingPort, ChannelRouter
├── adapters-shopify/   # Shopify GraphQL Admin API + receptor de webhooks HMAC SHA-256
├── adapters-meta/      # Meta Catalog API (publicación de catálogo, modo CATALOG_ONLY en CO)
├── adapters-channels/  # WhatsApp Cloud API, Instagram DM, Facebook Messenger (Send API)
├── infra/              # Supabase/PostgreSQL (Exposed), ExcelV2Parser (Apache POI),
│                       #   GeminiLlmAdapter, AppConfig (env vars + local.properties)
└── app/                # Servidor Ktor/Netty, rutas REST, webhooks, dashboard HTMX
```

---

## ⚙️ Configuración

Copia la plantilla y edita con tus datos reales:
```bash
cp local.properties.example local.properties
```

### Variables disponibles

```properties
# Puerto del servidor (por defecto: 8080)
PORT=8080

# Base de datos — Supabase PostgreSQL
# Si no configuras esto, el inventario solo se guarda en memoria (se pierde al reiniciar).
DATABASE_URL=jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres?sslmode=require
DATABASE_USER=postgres.ID_DEL_PROYECTO
DATABASE_PASSWORD=tu_clave

# Shopify
SHOPIFY_SHOP_DOMAIN=tu-tienda.myshopify.com
SHOPIFY_ACCESS_TOKEN=shpat_xxxxxxxxxxxxxxxxxxxxxxxx
SHOPIFY_WEBHOOK_SECRET=shpss_xxxxxxxxxxxxxxxxxxxxxxx

# Meta (catálogo, Instagram, Messenger)
META_CATALOG_ID=123456789
META_ACCESS_TOKEN=EAAGxxxxxxxxxxxxxxxxxxxxxxx
META_COUNTRY_CODE=CO
META_WEBHOOK_VERIFY_TOKEN=un_token_secreto_que_tú_eliges
META_GRAPH_API_VERSION=v26.0
META_APP_SECRET=                          # App Secret de Meta → verifica firma de webhooks

# WhatsApp Business Cloud API
WHATSAPP_PHONE_NUMBER_ID=1001234567890
WHATSAPP_ACCESS_TOKEN=EAAGxxxxxxxxxxxxxxxxxxxxxxx

# Instagram DM & Facebook Messenger
META_PAGE_ID=1234567890

# Texto que el bot envía cuando el cliente reserva (cómo pagar)
PAYMENT_INSTRUCTIONS=Paga por Nequi al 3XX XXX XXXX a nombre de TU NOMBRE.

# Protección del dashboard (HTTP Basic). Úsalo siempre detrás de HTTPS.
# Si DASHBOARD_PASSWORD está vacío, el tablero queda abierto (solo para pruebas locales).
DASHBOARD_USER=admin
DASHBOARD_PASSWORD=una_clave_larga_y_unica

# Gemini LLM (Opcional) — interpreta intenciones con IA. Si no se configura, el bot opera con el motor de reglas locales por expresiones regulares (sin IA).
GEMINI_API_KEY=AIzaSyxxxxxxxxxxxxxxxxxxxxxxx

# Opcionales de seguridad
REQUIRE_META_SIGNATURE=true               # Rechaza webhooks de Meta sin firma válida
CORS_ALLOWED_HOST=tu-dominio.com          # Restringe CORS; vacío = cualquier origen
```

> **DASHBOARD_PASSWORD** es obligatorio antes de exponer la app a Internet.  
> **META_APP_SECRET** es obligatorio para que los webhooks de Meta sean seguros.

---

## 🔐 Seguridad financiera

El sistema garantiza por diseño y por pruebas automatizadas que los campos sensibles —precio mínimo (`PM`), costo y margen— **nunca** se exponen a clientes, Shopify, Meta ni en la vitrina pública (`/public/p/{key}`).

Las columnas formuladas o calculadas (como el **Precio Mínimo `PM` = `costo_total × (1 + roi_minimo_pct)`**, **Costo Total**, **Margen** o **Ganancia**) **ignoran por diseño cualquier valor digitado manualmente en celdas del Excel**. El sistema recomputa deterministamente su propia fórmula interna (`FinancialMath.precioMinimo`), garantizando concordancia del 100% entre memoria, Excel y triggers de base de datos.

El motor de negociación (L0–L4) siempre cumple: $\text{Precio acordado} \ge L4 \ge PM$.

---

## 🔄 Flujo de una venta

1. **Cliente escribe** en WhatsApp / Instagram / Messenger.
2. El **pipeline** detecta la intención (consulta, oferta, reserva, pago).
3. Si hace una oferta, el **motor L0-L4** la evalúa:
   - ≥ PP → acepta al precio publicado.
   - Entre PM y PP → acepta con descuento escalonado.
   - < PM → contraoferta progresiva (nunca revela el PM).
4. Al reservar, descuenta una unidad del `stock_vivo` y envía instrucciones de pago.
5. Al recibir comprobante → escala a un humano y congela la reserva.
6. Reservas sin pago vencen en ~15 min y liberan el stock automáticamente.
7. Si la venta viene de Shopify (webhook `orders/create`), la **saga anti-sobreventa** retira el stock del resto de canales (Meta, etc.) en milisegundos.

---

## 🧪 Pruebas

```bash
./gradlew test
# o también:
python run_tests.py
```

La suite cubre: motor de negociación L0-L4, aislamiento financiero (fuga de PM/costo), saga anti-sobreventa, pipeline de mensajes, parseo/exportación de Excel, router de canales e idempotencia de eventos.

---

## 📁 Otros archivos

| Archivo | Descripción |
|---|---|
| `local.properties.example` | Plantilla de configuración (copia y edita) |
| `NEGOCIO.xlsx` | Plantilla de Matriz Comercial (sube desde el dashboard) |
| `MANUAL_DE_USO.md` | Manual operativo paso a paso |
| `LICENSE` | Licencia de código privado |

---

## 🔒 Licencia y Confidencialidad

**Código Privado y Confidencial.** Todos los derechos reservados. Ver [`LICENSE`](LICENSE).


## Nota de mantenimiento

El dashboard utiliza la versión oficial de htmx (`htmx.min.js` v1.9.10) alojada localmente, con configuración estricta vía `<meta name="htmx-config">` (`allowEval:false`, `allowScriptTags:false`, `selfRequestsOnly:true`) cumpliendo la directiva CSP (`script-src 'self'`).

## Registro de cambios y tareas pendientes

- `MANUAL_DE_CAMBIOS_v9.md`: cambios de esta entrega, limitaciones, pruebas pendientes y próximos pasos.
- `MANUAL_DE_CAMBIOS_v8.md`: primera entrega de módulos de socios y reparaciones.


## Registro de cambios reciente

La entrega de octubre de 2026 documenta los cambios de la matriz de inversión y las validaciones en `MANUAL_DE_CAMBIOS_v10.md`. Consulta también `MANUAL_DE_CAMBIOS_v9.md`.
