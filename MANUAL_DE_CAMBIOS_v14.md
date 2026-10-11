# Manual de cambios v14 — Contraoferta nunca por debajo de la oferta del cliente

## Resumen

Se corrige un defecto del motor de negociación (`NegotiationEngine`): en los pasos avanzados de contraoferta el bot podía
ofrecerle al cliente un precio **más barato que el que el propio cliente había propuesto**, regalando margen sin necesidad.
Ahora, si la contraoferta calculada es menor o igual a lo que el cliente ofreció, **se acepta la oferta del cliente**
(siempre que sea ≥ PM). El precio mínimo (PM) sigue siendo un piso inviolable.

| Tipo | Archivo | Qué pasó |
|---|---|---|
| Modificado | `commerce/src/main/kotlin/com/automata/commerce/service/NegotiationEngine.kt` | Se entrega completo |
| Nuevo | `commerce/src/test/kotlin/com/automata/commerce/NegotiationEngineCounterOfferTest.kt` | Test de regresión |
| Nuevo | `DIAGRAMA_DE_CLASES_v14.md` | Diagrama actualizado (archivo separado) |

No se tocó ningún otro archivo. No hay cambios de base de datos, configuración ni dependencias.

## Cómo instalar

Copia el contenido de este paquete **encima** de la raíz del proyecto (respetando las carpetas) y sobrescribe cuando lo pida.
Luego:

```bash
./gradlew :commerce:test        # prueba el motor y el nuevo test
./gradlew test                  # suite completa
```

## El problema

Las contraofertas bajan según el paso: 5 %, 10 % y 15 % de descuento sobre el Precio Publicado (PP), con una variación
estable de ±3 %. Una oferta se acepta automáticamente si descuenta como máximo 10 % (umbral L2). Una oferta con más de
10 % de descuento se rechaza y recibe contraoferta. En el **paso 2** la contraoferta es de 15 % de descuento; por lo tanto,
un cliente que pedía, por ejemplo, 12 % de descuento recibía una contraoferta **más barata** que su propia oferta
(la variación de ±3 % también podía producirlo en el paso 1).

Ejemplo con PP = 1.000.000 y PM = 700.000:

```
Cliente ofrece 880.000  →  contraoferta del paso 2: 850.000   (30.000 menos que lo que el cliente ofrecía)
```

No violaba el PM (el clamp funcionaba), pero perdía margen.

## El cambio exacto

Archivo `NegotiationEngine.kt`, función `evaluateOffer`, al final (justo después del chequeo L2). Diff:

```diff
@@ -118,7 +118,24 @@
         }
 
         // Cualquier oferta por debajo del umbral de aceptación (sea < PM o < L2) recibe la contraoferta del paso
-        return counterOffer(product, previousCounterOffers, customerId)
+        val counter = counterOffer(product, previousCounterOffers, customerId)
+
+        // Nunca contraofertar POR DEBAJO de lo que el cliente ya ofreció (v14): en los pasos avanzados (15 % de
+        // descuento, o el jitter de ±3 % en el paso 1) la contraoferta puede quedar más barata que la oferta del
+        // cliente. Como la contraoferta ya es >= PM (la fija calculateDiscountPrice/applyJitter), si el cliente
+        // ofreció igual o más, se acepta su oferta en vez de regalarle margen. La comprobación contra PM es
+        // redundante a propósito: el invariante "nunca aceptar bajo el PM" no debe depender de otra función.
+        val counterPrice = counter.counterOffer
+        if (counterPrice != null && offeredAmount >= counterPrice && offeredAmount >= pm) {
+            return NegotiationDecision(
+                accepted     = true,
+                level        = NegotiationLevel.L2_MODERATE_DISCOUNT,
+                agreedPrice  = offeredAmount,
+                counterOffer = null,
+                message = "Aceptamos tu oferta especial de ${offeredAmount.formatFormattedCop()} por hoy."
+            )
+        }
+        return counter
     }
 
     private fun counterOffer(product: CommercialProduct, previousCounterOffers: Int, customerId: String): NegotiationDecision {
```

### Por qué así

- **Se acepta la oferta del cliente, no se sube la contraoferta.** Si el cliente ya ofreció un valor ≥ al que el bot
  estaba dispuesto a ofrecer, aceptar es coherente y ahorra una ronda. El precio acordado es exactamente la oferta del
  cliente (nunca un valor inventado).
- **Nivel `L2_MODERATE_DISCOUNT`** y mensaje igual al de L2, para que el pipeline, el dashboard y el simulador
  (`DashboardRoutes`) lo traten como cualquier aceptación existente. No se agregaron enums ni campos nuevos.
- **`&& offeredAmount >= pm`** es redundante a propósito: la contraoferta ya es ≥ PM, pero el invariante
  «nunca aceptar bajo el PM» no debe depender de otra función.
- **`InboundMessagePipeline.handleOffer` no necesita cambios:** ya trata `accepted = true` reservando al
  `decision.agreedPrice`.

## Impacto en pruebas existentes

Revisé los tests que usan el motor (`NegotiationEngineTest`, `InboundMessagePipelineTest` y los demás del pipeline):
todos los escenarios de contraoferta usan ofertas **por debajo del PM** (0, 1.000, 50.000, 94.999 con PM = 95.000), donde la
nueva condición nunca se cumple. La prueba «oferta exacta en PM no se acepta automáticamente» (paso 0) tampoco cambia,
porque la contraoferta del paso 0 es ≥ 110.000. No se modificó ningún test existente.

## Test nuevo

`NegotiationEngineCounterOfferTest` comprueba, para 4 clientes × 3 pasos × todas las ofertas entre 95.000 y 107.999
(PP = 120.000, PM = 95.000):

1. Si hay contraoferta, es **estrictamente mayor** que la oferta del cliente, ≥ PM y ≤ PP.
2. Si se acepta, el precio acordado es exactamente la oferta del cliente y ≥ PM.
3. Una oferta de 107.000 en el paso 2 siempre se acepta a su propio precio.
4. Una oferta bajo el PM sigue sin aceptarse nunca.

## Cómo lo verifiqué (y qué no pude verificar)

En el entorno donde trabajé **no hay acceso a Maven ni a Gradle, así que no pude compilar ni ejecutar la suite Kotlin.**
Para validar la lógica, repliqué el motor completo (descuentos, redondeo, `hashCode` de Java para el jitter, clamp)
y lo ejecuté sobre 51.480 casos (5 combinaciones PP/PM, 4 clientes, 3 pasos, ofertas de 0 a 110 % del PP):

| | Contraoferta menor que la oferta del cliente | Aceptado por debajo del PM |
|---|---|---|
| Antes | **533** casos | 0 |
| Después | **0** casos | 0 |

Corre `./gradlew :commerce:test` en tu máquina para la confirmación final.

## Corrección a mi revisión anterior

En mi opinión del código dije que `abs(hashCode()) % 64` en `InboundMessagePipeline` podía fallar con
`Int.MIN_VALUE`. **Era incorrecto:** en la JVM `abs(Int.MIN_VALUE)` sigue siendo `Int.MIN_VALUE` y
`Int.MIN_VALUE % 64` es 0, un índice válido. No hay bug ahí, así que **no lo cambié** (`InboundMessagePipeline.kt`
queda exactamente como lo tienes).

## Nota de comportamiento

Para el cliente cambia esto: antes, en rondas tardías, podía recibir un precio menor al que pidió; ahora el bot le
acepta el suyo. Si prefieres que en ese caso siga negociando, hay que cambiar la política del negocio (por ejemplo,
contraofertar `max(contraoferta, oferta)`), pero eso volvería a darle al cliente menos de lo que ya aceptaría el bot.
