# Diagrama de clases — v6 (cambios de esta entrega)

Este diagrama muestra los módulos tocados y sus relaciones relevantes. No pretende representar todavía las funcionalidades pendientes de las fases 2–6.

```mermaid
classDiagram
    class ChatRoutes {
      +tomarConversacion()
      +devolverConversacion()
      +obtenerBandeja()
      +obtenerMensajes()
      +enviarMensajeHumano()
      +streamEventos()
    }
    class VentasRoutes {
      +registrarVentaManual()
      +actualizarLogistica()
      +asignarReparto()
      +anularVenta()
      +reintentarOutbox()
      +resolverAccionManual()
    }
    class DashboardGuard {
      +autenticar()
    }
    class ExposedTransaction {
      +exec(sql, args)
    }
    class PostgreSQL {
      +funciones tienda.*
      +vistas tienda.*
    }
    ChatRoutes --> DashboardGuard : autentica
    VentasRoutes --> DashboardGuard : autentica
    ChatRoutes --> ExposedTransaction : consultas parametrizadas
    VentasRoutes --> ExposedTransaction : consultas parametrizadas
    ExposedTransaction --> PostgreSQL : ejecuta funciones y consultas
```

## Cambios representados
- `ChatRoutes` y `VentasRoutes` siguen siendo las rutas HTTP del dashboard.
- Los valores variables de SQL se pasan como argumentos de Exposed, no concatenados al texto SQL.
- `DashboardGuard` sigue autenticando una credencial compartida; la identidad individual del operador aún no está implementada.
