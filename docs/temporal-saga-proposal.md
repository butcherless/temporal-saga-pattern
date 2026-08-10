# Propuesta: Saga Pattern con Temporal

> Documento comparativo entre la implementación custom actual de este repositorio (orquestador propio sobre Kafka + Outbox/Inbox) y una implementación equivalente usando **Temporal** como motor de *durable execution*. Fuentes: documentación oficial de Temporal ([`temporalio/documentation`](https://github.com/temporalio/documentation), [`temporalio/sdk-java`](https://github.com/temporalio/sdk-java)) vía Context7.

## 1. Resumen ejecutivo

Temporal sustituye el rol completo de `saga-orchestrator` (máquina de estados `SagaStatus`, tablas `saga_instance`/`saga_step`, Outbox/Inbox propio, retry/backoff manual, DLQ) por un **Workflow** cuyo código es simplemente el flujo de negocio escrito de forma imperativa (`try/catch`, bucles, llamadas secuenciales). El servidor de Temporal persiste automáticamente cada paso ejecutado (*event history*), reintenta actividades fallidas según una política declarativa, y sobrevive a caídas de proceso sin lógica de reanudación escrita a mano.

Decisión ya acordada para esta propuesta: las **Activities llaman directamente** a cada servicio de negocio vía HTTP/gRPC (síncrono), eliminando Kafka, Outbox y Inbox como mecanismo de comunicación orquestador↔servicios. Esto simplifica mucho el diseño pero implica exponer nuevos endpoints REST en `inventory-service` y `payment-service` (hoy solo consumen/producen Kafka).

## 2. Qué es Temporal (breve)

- **Workflow**: código que define la lógica de negocio de larga duración (aquí, la saga completa de una orden). Debe ser *determinista* — el servidor puede volver a ejecutar el código desde el historial persistido (*replay*) tras un fallo o restart de Worker.
- **Activity**: la unidad de trabajo con efectos secundarios (llamada HTTP, escritura en BD, etc.). No tiene restricciones de determinismo y es la única capa que toca el mundo exterior.
- **Worker**: proceso Java que se conecta al Temporal Server, hace *polling* de una *Task Queue* y ejecuta el código de Workflows/Activities.
- **Temporal Server**: el propio motor (Frontend/History/Matching/Worker services internos + almacén persistente). Soporta PostgreSQL como *datastore* — reutilizable con la infraestructura ya existente en este PoC.
- **Web UI**: visor del historial de cada ejecución (equivalente, sin instrumentación adicional, a lo que hoy se inspecciona a mano con Kafka UI + pgAdmin sobre `outbox_event`/`saga_step`).

## 3. Mapeo de conceptos: custom → Temporal

| Concepto actual (custom) | Equivalente en Temporal |
|---|---|
| `SagaStatus` (máquina de estados) + `SagaInstance.advanceTo` | Código imperativo dentro del método `run()` del Workflow — el "estado" es simplemente el punto de ejecución del código, capturado en el *event history*. |
| Tabla `saga_instance` | Estado del Workflow, persistido automáticamente por el servidor (nada que modelar/mantener). |
| Tabla `saga_step` (auditoría por paso) | *Event History* de la ejecución — visible íntegro en el Web UI, sin necesidad de una tabla ni de `writeSagaStep`. |
| `InboundMessageHandler` (tabla de dispatch comando→siguiente paso) | Llamadas directas a métodos de Activity, una por paso, en el orden que el propio código del Workflow expresa. |
| Retry/backoff manual (decisión 6: 3 intentos, backoff exponencial 2s→30s, reclasificación TEMPORARY→PERMANENT) | `RetryOptions`/`RetryPolicy` declarativo en `ActivityOptions` (backoff exponencial nativo, por defecto coeficiente 2.0, intervalo inicial 1s, máximo 100s, reintentos ilimitados salvo que se limite). Una excepción no-reintentable (`ApplicationFailure` marcado `nonRetryable`) hace de frontera TEMPORARY→PERMANENT sin lógica adicional. |
| Compensación (orden inverso, `SagaStepName.forStatus`) | Clase helper `io.temporal.workflow.Saga`: `saga.addCompensation(...)` antes de cada paso, `saga.compensate()` en el `catch` — ejecuta todo lo registrado en orden LIFO. |
| `COMPENSATION_FAILED` → `MANUAL_INTERVENTION_REQUIRED` | Una ejecución de Workflow que termina en fallo queda visible y consultable en el Web UI/API (`WorkflowClient` puede consultarla, reintentarla o terminarla manualmente) — mismo concepto, sin tabla de estado propia. |
| Outbox/Inbox del orquestador (`outbox_event`, `inbox_message`) | No aplica: no hay Kafka entre orquestador y servicios de negocio; las Activities son llamadas síncronas directas. |
| Dead Letter Queue (`dead_letter_attempt`, `saga.dlq.*.v1`, `reprocess-dlq.sh`) | No aplica en el orquestador (sin Kafka de por medio). El equivalente conceptual — "algo se quedó atascado, hay que intervenir" — es una ejecución de Workflow fallida, visible en el Web UI, reprocesable llamando de nuevo a la Activity o reiniciando el Workflow. |
| Idempotencia por `inbox_message` (dedup de mensajes Kafka) | Ya no hace falta a ese nivel (no hay mensajería). Se mantiene igual la idempotencia *de negocio* (reservar/liberar stock, cobrar/reembolsar, confirmar/cancelar orden por `businessKey`), y se recomienda una *idempotency key* derivada de `workflowRunId + activityId` para las llamadas HTTP salientes (patrón documentado por Temporal). |
| Escenarios #11/#12 (crash timing, fuera de alcance hoy) | Cubiertos "gratis": el *durable execution* de Temporal está diseñado exactamente para sobrevivir caídas de Worker en cualquier punto, sin necesidad de un test capaz de matar el proceso en el instante preciso. |

## 4. Qué se reutilizaría del proyecto actual

- **Lógica de dominio de los tres servicios de negocio**: `CustomerOrder`/`OrderProgressionService`, `StockItem`/`InventoryReservation`/`InventoryProgressionService`, `Payment`/`PaymentProgressionService` — las reglas de negocio (reservar/liberar stock, cobrar/reembolsar, confirmar/cancelar orden) no cambian; siguen viviendo en `domain`/`service` de cada servicio.
- **Persistencia de cada servicio**: repositorios R2DBC, migraciones Flyway, esquema de BD de dominio (`stock_item`, `inventory_reservation`, `payment`, `customer_order`) — sin cambios.
- **Idempotencia de negocio por `businessKey`/`sagaId`** (proposal §10) — sigue siendo obligatoria y se reutiliza tal cual; es independiente del mecanismo de transporte.
- **`POST /orders` de `order-service`** — se reutiliza como punto de entrada; en vez de publicar un evento a Kafka, pasa a iniciar el Workflow (`WorkflowClient.start(...)`).
- **`saga-common`**: los tipos de valor de negocio (p. ej. lo que hoy vive en `SagaPayload`: `sku`, `quantity`, `amount`) se reutilizan como *input*/*output* de Activities y Workflow. Las excepciones `TemporarySagaException`/`PermanentSagaException` se reutilizan como criterio para decidir, dentro de cada Activity, si relanzar la excepción tal cual (Temporal reintenta) o envolverla como `ApplicationFailure` no-reintentable (Temporal para y compensa) — el envelope de mensajería (`MessageHeader`, `Command`/`Event` base) deja de usarse para este flujo.
- **PostgreSQL en `docker-compose.yml`**: se reutiliza el mismo contenedor, añadiendo las bases `temporal`/`temporal_visibility` junto a las cuatro ya existentes (mismo patrón que `init-multiple-databases.sh`).
- **Escenarios de referencia de `platform-test/scripts/e2e-*.sh`**: el *qué* probar (happy path, error temporal con reintento, error permanente con compensación, fallo de compensación, duplicados) se reutiliza como checklist de aceptación; el *cómo* cambia (ya no hay que producir mensajes Kafka a mano — se puede usar `WorkflowClient`/`TestWorkflowEnvironment` directamente).
- **Convenciones transversales del repo**: Java 25, TDD, Javadoc en API pública, sin primitivos en firmas públicas, OpenAPI 3.1 code-first para los nuevos endpoints REST de negocio, Enforcer/OpenRewrite.

## 5. Qué sería nuevo

- **Temporal Server self-hosted** (vía `docker-compose`, imagen oficial `temporalio/auto-setup` o servicios separados) + **Temporal Web UI**, usando PostgreSQL como *datastore* (reutilizando el Postgres ya presente en el proyecto).
- **Nuevo módulo Maven** (p. ej. `saga-orchestrator-temporal`, sustituyendo a `saga-orchestrator`) con:
  - `workflow`: interfaz `@WorkflowInterface` (p. ej. `OrderSagaWorkflow`) + implementación con el flujo completo (reservar stock → cobrar pago → confirmar orden, con compensaciones registradas vía `Saga`).
  - `activities`: interfaces `@ActivityInterface` (una por capacidad: reservar/liberar stock, cobrar/reembolsar pago, confirmar/cancelar orden) + implementación como clientes HTTP hacia cada servicio de negocio.
  - Un **Worker** (proceso Spring Boot con `temporal-spring-boot-starter`) que registra Workflow + Activities sobre una Task Queue.
- **Nuevos endpoints REST en `inventory-service` y `payment-service`** (mismo patrón `web` + OpenAPI 3.1 code-first que ya usa `order-service`) — hoy estos dos servicios solo tienen `messaging`, no `web`.
- **Eliminación del paquete `messaging`** en los cuatro servicios (`KafkaMessagingConfig`, `InboundMessageHandler`/`Listener`, `OutboxPublisher`/`Scheduler`) y de las tablas `outbox_event`, `inbox_message`, `dead_letter_attempt`, `saga_instance`, `saga_step` — ya no hay nada que orquestar vía mensajería.
- **Nuevas dependencias**: `temporal-sdk`, `temporal-spring-boot-starter`, `temporal-testing` (para `TestWorkflowEnvironment`).
- **Nueva estrategia de test del orquestador**: `TestWorkflowEnvironment` con *time-skipping* (permite probar el backoff exponencial y timeouts sin esperas reales) en vez de Testcontainers-Kafka + JUnit; los servicios de negocio mantienen Testcontainers-Postgres para su propia capa de persistencia, más tests nuevos para los endpoints REST recién añadidos.
- **Manejo explícito de *idempotency key*** en cada Activity (`workflowRunId + activityId`, o el `businessKey` de negocio) al llamar a los nuevos endpoints REST, ya que el dedup por `inbox_message` desaparece.

## 6. Ventajas de Temporal sobre el custom

- **Durable execution real**: sobrevive caídas de proceso en cualquier punto del flujo sin lógica de reanudación escrita a mano — resuelve directamente los escenarios #11/#12 que hoy quedan fuera de alcance por ser difíciles de reproducir con temporización precisa vía bash.
- **Retry/backoff declarativo**: una `RetryPolicy` sustituye la lógica manual de reintentos + backoff + reclasificación (decisión 6), sin tabla `saga_instance.retry_count` ni scheduler de publicación con `next_attempt_at`.
- **Compensación de primera clase**: la clase `Saga` reemplaza la máquina de estados hecha a mano (`SagaStatus`, `SagaStepName.forStatus`) por código imperativo estándar (`try/catch` + lista de compensaciones).
- **Observabilidad sin instrumentar nada**: el Web UI muestra el historial completo de cada saga (entradas/salidas de cada Activity, reintentos, tiempos) — hoy eso exige mirar a mano Kafka UI + pgAdmin sobre tres tablas distintas.
- **Menos infraestructura propia**: desaparecen Outbox, Inbox y DLQ del orquestador (y, en este diseño, también de los servicios de negocio) — Temporal ya garantiza ejecución efectivamente-una-vez de cada Activity.
- **Testing más determinista**: *time-skipping* en tests permite validar backoff exponencial y timeouts sin esperas reales, algo que hoy obliga a scripts bash con sleeps reales.
- **Versionado de flujos en curso**: Temporal ofrece *patching*/versionado nativo para evolucionar un Workflow ya desplegado sin romper ejecuciones en curso — problema que hoy no está resuelto (Flyway es append-only para el esquema, pero no hay estrategia para sagas "en vuelo" ante un cambio de lógica).
- **Menos código de mensajería repetido ×4**: `KafkaMessagingConfig`/`InboundMessageHandler`/`OutboxPublisher`/`OutboxPublisherScheduler`, casi idénticos en los cuatro servicios, se sustituyen por definiciones de Workflow/Activity mucho más cortas.

## 7. Desventajas / trade-offs a considerar

- **Nueva pieza de infraestructura a operar**: el propio Temporal Server (aunque en self-hosted simple corre como un único contenedor con Postgres, en producción real son varios servicios internos con necesidades de escalado distintas — ver el *self-hosting checklist* oficial).
- **Curva de aprendizaje propia**: reglas de determinismo dentro del código de Workflow (nada de I/O, tiempo real, aleatoriedad no determinista directamente en `run()`), distintas del modelo mental "servicio reactivo + Kafka" que domina el resto del repo.
- **El ejercicio deja de investigar Kafka/Outbox/Inbox como mecanismo**: si parte del objetivo original de este PoC era entender esos detalles en un contexto de mensajería asíncrona, Temporal los abstrae — vale la pena documentarlo como una comparación complementaria, no como sustituto de ese aprendizaje.
- **Self-hosted vs Temporal Cloud**: para este PoC lo natural es self-hosted vía `docker-compose` (reutilizando el mismo Postgres), pero eso no refleja la complejidad operativa de un despliegue productivo real (alta disponibilidad, escalado, seguridad — ver checklist oficial).
- **Nueva superficie de API síncrona**: `inventory-service` y `payment-service` pasan de ser puramente orientados a eventos a exponer también REST — no cambia su naturaleza reactiva interna, pero es código nuevo que hoy no existe.

## 8. Estructura de módulos propuesta (orientativa)

```
saga-orchestrator-temporal/
├── workflow/     OrderSagaWorkflow (interfaz + impl, usa Saga para compensación)
├── activities/   interfaces @ActivityInterface + impl (clientes HTTP)
└── client/       clientes hacia order-service/inventory-service/payment-service
                  (ideal: generados desde el OpenAPI 3.1 de cada servicio)

inventory-service/
└── web/          nuevo — POST /reservations, POST /reservations/{id}/release

payment-service/
└── web/          nuevo — POST /payments, POST /payments/{id}/refund
```

Un único Worker (dentro de `saga-orchestrator-temporal`) alojando Workflow + Activities sobre una sola Task Queue es la opción más simple y coherente con "Activities llaman directo"; la alternativa (un Worker por servicio de negocio, cada uno con su propia Task Queue) añade aislamiento de despliegue pero no aporta nada mientras las Activities sean solo clientes HTTP sin estado.

## 9. Snippet ilustrativo (Java SDK, patrón Saga)

```java
@WorkflowInterface
public interface OrderSagaWorkflow {
    @WorkflowMethod
    void process(OrderSagaInput input);
}

public class OrderSagaWorkflowImpl implements OrderSagaWorkflow {
    private final SagaActivities activities = Workflow.newActivityStub(
        SagaActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(2))
                .setBackoffCoefficient(2.0)
                .setMaximumInterval(Duration.ofSeconds(30))
                .setMaximumAttempts(3)
                .build())
            .build());

    @Override
    public void process(OrderSagaInput input) {
        Saga saga = new Saga(new Saga.Options.Builder().build());
        try {
            activities.reserveInventory(input);
            saga.addCompensation(activities::releaseInventory, input);

            activities.chargePayment(input);
            saga.addCompensation(activities::refundPayment, input);

            activities.confirmOrder(input);
        } catch (Exception e) {
            saga.compensate();
            throw e;
        }
    }
}
```

Equivalente directo del flujo hoy repartido entre `InboundMessageHandler`, la tabla de dispatch de comandos y `SagaProgressionService` — aquí es, literalmente, el cuerpo de un método.

## 10. Conclusión

Adoptar Temporal para este ejercicio cambiaría el foco de aprendizaje: de "cómo construir a mano los mecanismos de fiabilidad (Outbox, Inbox, retry/backoff, compensación, DLQ)" a "cómo modelar una saga cuando esos mecanismos ya vienen resueltos por la plataforma". Ambos enfoques son legítimos para el objetivo original del PoC (entender el patrón Saga a fondo); Temporal muestra el mismo problema de negocio con muchísimo menos código propio de infraestructura, al costo de operar un sistema adicional y de dejar de practicar esos mecanismos de bajo nivel.
