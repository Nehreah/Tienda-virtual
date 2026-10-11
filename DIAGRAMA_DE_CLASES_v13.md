# Diagrama de clases v13 — Tabla de reparaciones del dashboard (completo)

Muestra las clases que **cambian en v13** (marcadas `MODIFICADO_v13` / `NUEVO_v13`) y con quién se relacionan.
El diagrama anterior es `DIAGRAMA_DE_CLASES_v9.md` (v10, v11 y v12 no actualizaron diagrama; lo de v12 sobre `ConfirmPaymentUseCase`, `Money` y `ProductKey` sigue vigente tal como está en v9).
Se visualiza en GitHub, VS Code (extensión Mermaid) o en https://mermaid.live

```mermaid
classDiagram
    direction LR

    class DashboardJs {
      <<MODIFICADO_v13>>
      +loadPanel(config)
      muestra el HTML de un error 4xx/5xx NUEVO_v13
      no marca data-loaded si hubo error NUEVO_v13
    }

    class ReparacionesRoutes {
      <<MODIFICADO_v13>>
      +GET /ui/reparaciones
      +POST /api/reparaciones/key
      -panel(inventario, reparaciones, mensaje, error, status) NUEVO_v13
      responde fragmentos HTML, ya no JSON ni redirect
    }
    class ReparacionesRenderer {
      <<MODIFICADO_v13>>
      +render(items, reparaciones, error, mensaje) String
      formulario con hx-post y hx-target NUEVO_v13
      clave codificada en la URL NUEVO_v13
      parametro borrador NUEVO_v13
      muestra mensaje de exito NUEVO_v13
    }
    class RouteSupport {
      +respondFragmento(html, status)
      +mensajeLegible() String
    }
    class DashboardGuard
    class InventarioRepository {
      <<interface>>
      +findAll() List
      +findByKey(key) InventarioItem
    }
    class ReparacionRepository {
      <<interface>>
      +find(key) Reparacion
      +findAll() List  NUEVO_v13
      +guardar(rep, usuario, origen) Reparacion
    }
    class ExposedReparacionRepository {
      <<MODIFICADO_v13>>
      +findAll() usa selectAll  NUEVO_v13
    }
    class ApplicationModule {
      <<MODIFICADO_v13>>
      dbRepository segun databaseConnected
    }
    class Reparacion {
      +key ProductKey
      +precioReparacion Money
      +detalles String
    }
    class InventarioItem {
      +key ProductKey
      +requiereReparacion Boolean
      +precioCompra Money
    }
    class ReparacionesRendererTest {
      <<NUEVO_v13>>
      +formulario htmx y texto escapado
      +clave codificada y borrador
      +mensaje y error escapados
    }

    ApplicationModule --> ReparacionesRoutes : inyecta repositorios
    DashboardJs ..> ReparacionesRoutes : GET /ui/reparaciones
    ReparacionesRoutes --> DashboardGuard : requireDashboardAuth
    ReparacionesRoutes --> InventarioRepository : findAll y findByKey
    ReparacionesRoutes --> ReparacionRepository : find y guardar
    ReparacionesRoutes --> ReparacionesRenderer : render
    ReparacionesRoutes ..> RouteSupport : respondFragmento y mensajeLegible
    ExposedReparacionRepository ..|> ReparacionRepository
    ReparacionRepository ..> Reparacion
    InventarioRepository ..> InventarioItem
    ReparacionesRenderer ..> InventarioItem
    ReparacionesRenderer ..> Reparacion
    ReparacionesRendererTest ..> ReparacionesRenderer : prueba
```

## Flujo de guardar una reparación en v13

```mermaid
flowchart TD
    A[Formulario hx-post /api/reparaciones/key] --> B{BD disponible?}
    B -- no --> C[503 con fragmento: requiere PostgreSQL]
    B -- si --> D{precio y clave validos?}
    D -- no --> E[400 con panel y error]
    D -- si --> F{existe el articulo?}
    F -- no --> G[404 con panel y error]
    F -- si --> H[reparacionRepo.guardar]
    H -- excepcion SQL, ej. articulo vendido --> I[409 con panel y mensaje legible]
    H -- OK --> J[200 con panel y mensaje de exito]
    C --> K[dashboard.js o htmx muestran el fragmento]
    E --> K
    G --> K
    I --> K
    J --> K
```
