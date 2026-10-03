# Autómata Comercial Multicanal v5.3

> **Código Privado y Confidencial** — Arquitectura Hexagonal / Monolito Modular en Kotlin + Ktor + PostgreSQL + Shopify GraphQL API & Webhooks + Meta APIs (WhatsApp, Instagram, Messenger).

Plataforma comercial desacoplada que separa la **Matriz Comercial (Excel v2)** que define qué se vende y bajo qué condiciones, del **Estado Operacional (PostgreSQL / DB)** que permite ejecutar, sincronizar y controlar las ventas multicanal.

---

## ⚡ Inicio Rápido en 1 Clic

### En Linux / macOS:
```bash
./start.sh
```

### En Windows:
```cmd
start.bat
```

El script verificará la configuración, creará `local.properties` si no existe e iniciará la aplicación en:
👉 **http://localhost:8080**

---

## 🚀 Características Principales

1. **Separación Estricta de Dominios**:
   - **Matriz Comercial (`Excel v2`)**: Fuente autorizada de productos (`Key`), precios publicados (`PP`), precio mínimo de reserva (`PM`), costo, margen, fotos, descripciones `AB` y títulos por canal.
   - **Aislamiento Financiero Inquebrantable**: `PM`, `costo`, `ganancia` y `margen` son información estrictamente interna. El sistema garantiza mediante pruebas automatizadas de frontera que estos campos **nunca** se transmitan a Shopify, Meta, Mercado Libre, DTOs públicos ni vitrina web.
   - **Base Operacional (`PostgreSQL`)**: Controla en tiempo real el `stock_vivo = stock_maestro - reservas_activas - unidades_vendidas`, mapeos de IDs externos (`external_product_mappings`), cola de Outbox y máquina de estados de chat.

2. **Integración Shopify GraphQL Admin API & Webhooks**:
   - Mutaciones GraphQL de catálogo (`productCreate`, `productUpdate`, `inventorySetQuantities`).
   - Receptor de Webhooks firmado con **HMAC SHA-256** (`orders/create`, `inventory/update`).

3. **Canales de Mensajería Nativos de Meta**:
   - **WhatsApp Business Cloud API** (`Graph API`, versión configurable con `META_GRAPH_API_VERSION`, tarjetas interactivas de catálogo).
   - **Instagram DM & Facebook Messenger** (Send API, misma versión configurable).
   - Receptor de Webhooks unificado (`GET/POST /webhooks/meta` con validación `hub.challenge`).

4. **Saga Anti-Sobreventa & Retiro Multicanal**:
   - Al confirmarse una venta en cualquier canal (ej. Shopify webhook), la saga ajusta a 0 o retira automáticamente las existencias en los demás canales en milisegundos.

5. **Motor Comercial de Negociación (L0–L4)**:
   - Escalera de concesiones comerciales respetando con invarianza absoluta $Precio \ge L4 \ge PM$.

6. **Dashboard Operacional Temprano**:
   - Interfaz web interactiva en HTMX / Ktor con previsualización del Excel, diff de cambios, estado de Shopify y Meta, y simulador de negociación en vivo.

---

## 🛠️ Estructura del Proyecto (`Monolito Modular`)

```
automata-comercial-multicanal/
├── start.sh            # Script de inicio rápido en 1 clic para Linux/macOS
├── start.bat           # Script de inicio rápido en 1 clic para Windows
├── shared-kernel/      # Money (Long COP), ProductKey, PortResult, Eventos de Dominio
├── commerce/           # CommercialProduct, OperationalInventory, Motor L0-L4, Saga
├── messaging/          # Máquina de estados de conversación, LlmPort, MessagingPort
├── adapters-shopify/   # Adaptador Shopify GraphQL API & Receptor HMAC Webhooks
├── adapters-meta/      # Adaptador Meta Catalog API (CATALOG_ONLY en Colombia)
├── adapters-channels/  # Adaptadores WhatsApp Cloud API, Instagram DM, Messenger
├── infra/              # PostgresTables, ExcelV2Parser (3 hojas POI), GeminiLlmAdapter, AppConfig
├── app/                # Servidor Ktor Netty, REST API, Webhooks y Dashboard HTMX
├── local.properties.example # Plantilla de configuración
├── local.properties    # Configuración local (IGNORADO EN GIT)
├── NEGOCIO.xlsx        # Archivo de muestra de Matriz Comercial
├── MANUAL_DE_USO.md    # Manual paso a paso de uso
├── README.md           # Documentación técnica
└── LICENSE             # Licencia de código privado
```

---

## ⚙️ Configuración y Uso

### 1. Configuración de Credenciales (`local.properties`)
Edita `local.properties` y asigna tus credenciales reales:
```properties
PORT=8080
SHOPIFY_SHOP_DOMAIN=tu-tienda.myshopify.com
SHOPIFY_ACCESS_TOKEN=shpat_tu_token_aqui
SHOPIFY_WEBHOOK_SECRET=shpss_tu_secret_aqui
META_CATALOG_ID=meta_cat_123456
META_ACCESS_TOKEN=EAAG_tu_token_aqui
WHATSAPP_PHONE_NUMBER_ID=1001234567890
META_PAGE_ID=page_1234567890
GEMINI_API_KEY=AIzaSy_tu_key_aqui
META_APP_SECRET=el_app_secret_de_tu_app_de_meta   # firma de los webhooks de Meta
PAYMENT_INSTRUCTIONS=Paga por Nequi al 3XX XXX XXXX a nombre de TU NOMBRE.
DASHBOARD_USER=admin
DASHBOARD_PASSWORD=una_clave_larga_y_unica        # protege el tablero y /api/* (HTTP Basic); úsalo detrás de HTTPS
REQUIRE_META_SIGNATURE=true                       # (opcional) /webhooks/meta rechaza todo si falta META_APP_SECRET
CORS_ALLOWED_HOST=tu-dominio.com                  # (opcional) restringe CORS a tu dominio; vacío = cualquiera
```

### 2. Ejecutar la Suite de Pruebas

Para correr todas las pruebas unitarias y de seguridad financiera:
```bash
./gradlew test          # o: python run_tests.py (ejecuta Gradle de verdad y devuelve su código de salida)
```

---

## 🔒 Licencia y Confidencialidad
Este software es **Código Privado y Confidencial**. Todos los derechos reservados. Consultar el archivo [`LICENSE`](LICENSE) para más detalles.
