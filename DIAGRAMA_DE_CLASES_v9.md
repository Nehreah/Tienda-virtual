# Diagrama de clases v9 — Pago idempotente, resultado sin BD y ajustes de `Money`

Muestra las clases que **cambian en v9** (marcadas `NUEVO_v9` / `MODIFICADO_v9`) y con quién se relacionan.
El diagrama completo de base sigue siendo `DIAGRAMA_DE_CLASES_v4.md` más los cambios de v6, v7 y v8.
Se visualiza en GitHub, VS Code (extensión Mermaid) o en https://mermaid.live

```mermaid
classDiagram
    direction LR

    class PagosRoutes {
      <<MODIFICADO_v9>>
      +POST /api/confirmar-pago
      omite recordSale si isIdempotentRepeat
      avisa si persisted es false
    }

    class ConfirmPaymentUseCase {
      <<MODIFICADO_v9>>
      +execute(request) ConfirmPaymentResult
      -resolverReintento(request, causa) ConfirmPaymentResult
      -normalizarCanal(canal) String
    }
    class ConfirmPaymentRequest {
      +dealId String
      +productKey ProductKey
      +salePrice Money
      +channel String
      +user String
    }
    class ConfirmPaymentResult {
      <<MODIFICADO_v9>>
      +success Boolean
      +isIdempotentRepeat Boolean
      +item InventarioItem
      +errorMessage String
      +persisted Boolean  NUEVO_v9
    }

    class InventarioRepository {
      <<interface>>
      +findByKey(key) InventarioItem
      +marcarVendido(key, vendidoEn, fecha, canal, usuario, origen) InventarioItem
    }
    class ExposedInventarioRepository {
      <<MODIFICADO_v9>>
      +marcarVendido() usa SELECT FOR UPDATE
      +upsert() sin operador de doble exclamacion
    }
    class InMemoryInventarioRepository
    class InventarioItem {
      +estaVendido Boolean
      +vendidoEn Money
      +canalVenta String
      +registrarVenta(vendidoEn, fecha, canal) InventarioItem
    }

    class Money {
      <<MODIFICADO_v9>>
      +amountInCop Long
      +plus(other) Money
      +minus(other) Money  error claro si es negativo
      +signedDiff(other) SignedCop  NUEVO_v9
      +formatCop() String  NUEVO_v9
      +formatFormattedCop() String  OBSOLETO_v9
    }
    class SignedCop {
      +amount Long
      +format() String
    }
    class ProductKey {
      <<MODIFICADO_v9>>
      +validar(raw) String
      +parse(raw) ProductKey
      +parseOrNull(raw) ProductKey
    }
    class NegotiationLevel {
      <<enumeration>>
      L0_PUBLISHED_PRICE
      L1_LIGHT_DISCOUNT
      L2_MODERATE_DISCOUNT
      REJECTED_BELOW_PM
      ESCALATED_HUMAN
    }
    class NegotiationEngine {
      <<MODIFICADO_v9>>
      +evaluateOffer() NegotiationDecision
    }

    PagosRoutes --> ConfirmPaymentUseCase : llama a execute
    ConfirmPaymentUseCase ..> ConfirmPaymentRequest : recibe
    ConfirmPaymentUseCase ..> ConfirmPaymentResult : devuelve
    ConfirmPaymentUseCase --> InventarioRepository : marcarVendido y findByKey
    ExposedInventarioRepository ..|> InventarioRepository
    InMemoryInventarioRepository ..|> InventarioRepository
    InventarioRepository ..> InventarioItem
    ConfirmPaymentRequest --> Money
    ConfirmPaymentRequest --> ProductKey
    InventarioItem --> Money
    Money ..> SignedCop : signedDiff
    NegotiationEngine ..> NegotiationLevel
```

## Flujo de `execute` en v9

```mermaid
flowchart TD
    A[execute request] --> B{repo es null?}
    B -- si --> C[success true, persisted false]
    B -- no --> D[marcarVendido con candado de fila]
    D -- OK --> E[success true, item]
    D -- producto no existe --> F[success false, No se encontro]
    D -- IllegalStateException --> G[resolverReintento: releer producto]
    D -- otra excepcion --> H[success false, Error en BD]
    G --> I{esta vendido?}
    I -- no --> J[success false, motivo original]
    I -- si --> K{mismo precio y canal?}
    K -- si --> L[success true, isIdempotentRepeat true]
    K -- no --> M[success false, conflicto]
```
