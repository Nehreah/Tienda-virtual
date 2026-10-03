# Manual de Uso: Tienda Virtual Multicanal, Semiautogestionada

Bienvenido al **Manual de Uso del Tienda Virtual Multicanal, Semiautogestionada (v5.3)**. Este documento explica cómo operar la plataforma, actualizar el catálogo mediante la Matriz Comercial en Excel, supervisar las operaciones en el Dashboard Web y utilizar la integración automatizada con **Shopify GraphQL Admin API** y **Meta Commerce Manager**.

---

## 📌 1. Concepto Fundamental de Operación

La plataforma opera bajo el principio de **desacoplamiento de información**:

- **Excel (`NEGOCIO.xlsx`) = Matriz Comercial**: Es la fuente autorizada donde defines **QUÉ vendes y bajo QUÉ condiciones** (productos, precios públicos, precios mínimos, descripciones, fotos, reglas por canal).
- **PostgreSQL / BD = Base Operacional**: Mantiene el **ESTADO EN TIEMPO REAL** (existencias vivas, reservas de clientes por chat, publicaciones vinculadas en Shopify/Meta, cola de tareas pendientes y registros de auditoría).

> [!IMPORTANT]
> **Seguridad Financiera Protegida**: Los datos de **Precio Mínimo (`PM`)**, **Costo interno**, **Ganancia** y **Porcentaje de Margen** son estrictamente privados. El sistema los utiliza internamente para validar ofertas y comisiones, pero **NUNCA los transmite a Shopify, Meta, Mercado Libre ni a los clientes**.

---

## 📊 2. Estructura de la Matriz Comercial (`NEGOCIO.xlsx`)

El archivo Excel consta de **3 hojas activas** y una hoja de configuración:

### Hoja 1: `Inventario` (Información Maestro Comercial)
Contiene la lista maestra de tus productos:
- **`Key`**: Identificador único e inmutable (ej. `PROD-001`). Nunca debe repetirse.
- **`Item name`**: Nombre comercial del producto.
- **`PP`**: Precio Publicado de venta al público (ej. `$120.000`).
- **`PM`**: Precio Mínimo absoluto permitido para negociaciones (ej. `$95.000`). *Dato sensible interno.*
- **`Costo`**: Costo de adquisición o fabricación (ej. `$70.000`). *Dato sensible interno.*
- **`Estado`**: `Activo` o `Inactivo`.
- **`Descripción AB`**: Descripción detallada del producto para publicaciones y chats.
- **`Cantidad disponible`**: Stock Maestro físico base.

### Hoja 2: `Publicacion` (Datos exigidos por Canales)
- **`Key`**: Debe coincidir exactamente con la clave de la hoja `Inventario`.
- **`Marca`**: Marca oficial (ej. `AudioTech`).
- **`Condición`**: `Nuevo`, `Reacondicionado` o `Usado`.
- **`SKU interno`**: Código SKU de inventario.
- **`GTIN / EAN`**: Código de barras de 8, 12 o 13 dígitos.
- **`MPN`**: Número de pieza del fabricante.
- **`Foto 1 ... Foto 8`**: Enlaces HTTPS directos a imágenes JPEG o PNG.

### Hoja 3: `Canales` (Overrides por Plataforma)
- **`Key`**: Clave del producto.
- **`Canal`**: Nombre del canal (`shopify`, `meta_catalogo`, `mercadolibre`).
- **`Publicar`**: `SÍ` o `NO`.
- **`Título para el canal`**: Título personalizado si requiere un formato especial en ese canal.
- **`Precio del canal`**: Precio ajustado opcional (ej. para incluir comisión de plataforma).

---

## 🛠️ 3. Guía Paso a Paso para Operar el Sistema

### Paso 1: Configurar Credenciales Locales
1. Copia el archivo de plantilla en la raíz del proyecto:
   ```bash
   cp local.properties.example local.properties
   ```
2. Abre `local.properties` y configura tus credenciales de Shopify y Meta:
   ```properties
   PORT=8080
   SHOPIFY_SHOP_DOMAIN=tu-tienda.myshopify.com
   SHOPIFY_ACCESS_TOKEN=shpat_xxxxxxxxxxxxxxxxxxxxxxxx
   SHOPIFY_WEBHOOK_SECRET=shpss_xxxxxxxxxxxxxxxxxxxxxxx
   META_CATALOG_ID=meta_cat_123456
   META_ACCESS_TOKEN=EAAGxxxxxxxxxxxxxxxxxxxxxxx
   ```

### Paso 2: Iniciar la Aplicación
Ejecuta el servidor Ktor Netty en la terminal:
```bash
./gradlew run
```
Verás el mensaje de confirmación:
`Iniciando Tienda Virtual Multicanal, Semiautogestionada en http://localhost:8080`

### Paso 3: Abrir el Dashboard Web
Abre tu navegador e ingresa a:
```
http://localhost:8080
```

---

## 🖥️ 4. Uso del Dashboard Operacional

En el Dashboard encontrarás 3 secciones principales:

1. **Indicadores de Estado Superior**:
   - Muestra el total de productos en catálogo, cuántos están sincronizados activamente en Shopify GraphQL API y cuántos en Meta Commerce Manager.

2. **Carga y Sincronización de Excel v2**:
   - Haz clic en **"Seleccionar archivo"**, elige tu archivo `NEGOCIO.xlsx` actualizado y pulsa **"Cargar y Sincronizar Operación"**.
   - El sistema validará que los precios mínimos respeten las reglas financieras y reajustará el stock vivo en PostgreSQL.

3. **Simulador de Negociación L0–L4 (Live Engine)**:
   - Permite probar cómo responderá el bot de ventas ante ofertas de clientes.
   - Escribe un mensaje como: `Te ofrezco 100.000 por los audífonos PROD-001`.
   - Verás el nivel de la escalera activado (`L0`, `L1`, `L2`, `L3`, `L4`) y la respuesta generada sin violar el PM.

---

## 🤖 5. Motor de Negociación (Escalera L0–L4)

El bot de ventas evalúa automáticamente las propuestas de precio de los clientes mediante las siguientes reglas:

- **Nivel L0 (Precio Publicado - PP)**: Oferta igual o superior al precio de lista. Se acepta de inmediato.
- **Nivel L1 (Descuento Leve)**: Oferta con pequeña concesión. Se acepta automáticamente.
- **Nível L2 / L3 (Descuento Moderado / Alto)**: Concesión gradual según volumen o pronto pago.
- **Nivel L4 (Límite Infranqueable)**: Límite máximo de descuento automático.
- **Rechazo por debajo del PM**: Si la oferta del cliente cae por debajo del Precio Mínimo (`PM`), la propuesta se **rechaza automáticamente** y el bot genera una contraoferta amable en el nivel L4.

---

## ⚡ 6. Integración con Shopify y Saga Anti-Sobreventa

1. **Sincronización GraphQL**:
   - Cuando agregas o actualizas un producto en el Excel, el adaptador de Shopify ejecuta las mutaciones GraphQL `productCreate` y `inventorySetQuantities` para mantener actualizado el catálogo web.

2. **Recepción de Webhooks de Venta**:
   - Cuando un cliente compra un producto en tu tienda Shopify, Shopify envía un webhook a `/webhooks/shopify` firmado con `HMAC SHA-256`.

3. **Saga Anti-Sobreventa Instantánea**:
   - Al recibir la confirmación de la venta, la saga:
     1. Registra la venta y descuenta el stock vivo en PostgreSQL.
     2. Ajusta en milisegundos las existencias a `0` o al stock restante en Meta y Mercado Libre.
     3. Cancela cualquier reserva temporal pendiente del mismo producto en chats activos.

---

## ❓ 7. Preguntas Frecuentes (FAQ)

### ¿Qué ocurre si modifico la cantidad de un producto en el Excel?
El sistema toma el nuevo valor como **Stock Maestro**, descuenta las unidades ya vendidas y las reservas activas en curso, y actualiza el **Stock Vivo Operacional** sin afectar a los clientes que están en proceso de compra.

### ¿Dónde puedo consultar los productos de cara al público?
Puedes consultar el DTO público seguro de cualquier producto mediante la URL pública:
`http://localhost:8080/public/p/PROD-001`
Verificarás que esta respuesta **carece completamente** de PM, costos o márgenes.

### ¿Qué hago si falla la conexión a internet?
El sistema utiliza el patrón **Outbox**. Las tareas de actualización de stock pendientes se guardan en la tabla `com_outbox` de PostgreSQL y se reintentan automáticamente cuando se restablece la conexión.
