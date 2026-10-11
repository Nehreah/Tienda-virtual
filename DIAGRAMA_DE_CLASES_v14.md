# Diagrama de clases v14 — Motor de negociación

Muestra las clases que **cambian en v14** (`MODIFICADO_v14` / `NUEVO_v14`) y con quién se relacionan.
El diagrama anterior es `DIAGRAMA_DE_CLASES_v13.md` (tabla de reparaciones del dashboard); lo que v14 no menciona sigue
vigente tal como está allí. v14 no modifica `InboundMessagePipeline`, `DashboardRoutes` ni el modelo de datos.
Se visualiza en GitHub, VS Code (extensión Mermaid) o en https://mermaid.live

```mermaid
classDiagram
    direction LR

    class NegotiationEngine {
      <<MODIFICADO_v14>>
      -random Random
      +evaluateOffer(product, offeredAmount, previousCounterOffers, customerId) NegotiationDecision
      -counterOffer(product, previousCounterOffers, customerId) NegotiationDecision
      -applyJitter(raw, pm, pp, customerId, step) Money
      -calculateDiscountPrice(pp, pm, discountPercent) Money
      acepta la oferta del cliente si es mayor o igual a la contraoferta NUEVO_v14
      guarda explicita offeredAmount mayor o igual a PM NUEVO_v14
    }
    class NegotiationDecision {
      +accepted Boolean
      +level NegotiationLevel
      +agreedPrice Money
      +counterOffer Money
      +message String
      +isEscalatedToHuman Boolean
      +isFinalCounter Boolean
    }
    class NegotiationLevel {
      <<enumeration>>
      L0_PUBLISHED_PRICE
      L1_LIGHT_DISCOUNT
      L2_MODERATE_DISCOUNT
      REJECTED_BELOW_PM
      ESCALATED_HUMAN
    }
    class CommercialProduct {
      +publishedPrice Money
      +minimumPrice Money
    }
    class Money {
      +amountInCop Long
      +formatFormattedCop() String
    }
    class InboundMessagePipeline {
      -handleOffer(conv, product, offered, available, now) PipelineOutcome
    }
    class DashboardRoutes {
      +POST simulador de chat
    }
    class NegotiationEngineTest
    class NegotiationEngineCounterOfferTest {
      <<NUEVO_v14>>
      +contraoferta nunca menor que la oferta del cliente
      +oferta sobre la contraoferta del paso 2 se acepta
      +oferta bajo PM nunca se acepta
    }

    InboundMessagePipeline --> NegotiationEngine : evaluateOffer
    DashboardRoutes --> NegotiationEngine : evaluateOffer
    NegotiationEngine ..> CommercialProduct : lee PP y PM
    NegotiationEngine ..> NegotiationDecision : devuelve
    NegotiationEngine ..> Money
    NegotiationDecision --> NegotiationLevel
    NegotiationDecision ..> Money
    NegotiationEngineTest ..> NegotiationEngine : prueba
    NegotiationEngineCounterOfferTest ..> NegotiationEngine : prueba
```

## Flujo de `evaluateOffer` en v14

```mermaid
flowchart TD
    A[evaluateOffer] --> B{contraofertas previas >= 3?}
    B -- si --> Z[Escala a humano]
    B -- no --> C{oferta >= PP?}
    C -- si --> L0[Acepta L0 a PP]
    C -- no --> D{PM >= PP?}
    D -- si --> F[Rechaza: sin margen, contraoferta = PP]
    D -- no --> E{oferta >= precio con 5 por ciento dto?}
    E -- si --> L1[Acepta L1 a la oferta]
    E -- no --> G{oferta >= precio con 10 por ciento dto?}
    G -- si --> L2[Acepta L2 a la oferta]
    G -- no --> H[Calcula contraoferta del paso, siempre >= PM]
    H --> I{oferta >= contraoferta y oferta >= PM? NUEVO_v14}
    I -- si --> L2b[Acepta L2 a la oferta del cliente NUEVO_v14]
    I -- no --> J[Devuelve la contraoferta]
```
