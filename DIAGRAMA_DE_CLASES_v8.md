# Diagrama de clases v8 — Fase 3 (primera entrega)

```mermaid
classDiagram
    class Application {
      +module()
    }
    class ReparacionesRoutes {
      +GET /ui/reparaciones
      +POST /api/reparaciones/{key}
    }
    class ReparacionesRenderer {
      +render(items, reparaciones, error) String
    }
    class ReparacionRepository {
      <<interface>>
      +find(key) Reparacion
      +guardar(rep, usuario, origen) Reparacion
    }
    class ExposedReparacionRepository
    class InventarioRepository {
      <<interface>>
      +findAll() List~InventarioItem~
      +findByKey(key) InventarioItem
    }
    class ExposedInventarioRepository
    class Reparacion {
      +key ProductKey
      +precioReparacion Money
      +detalles String
    }
    class SocioService {
      +renombrar(id, nuevoNombre) Socio
    }
    class SociosRoutes {
      +PUT /api/socios/{id}
    }
    Application --> ReparacionesRoutes
    ReparacionesRoutes --> ReparacionRepository
    ReparacionesRoutes --> InventarioRepository
    ReparacionesRoutes --> ReparacionesRenderer
    ExposedReparacionRepository ..|> ReparacionRepository
    ExposedInventarioRepository ..|> InventarioRepository
    ReparacionRepository ..> Reparacion
    SociosRoutes --> SocioService
```
