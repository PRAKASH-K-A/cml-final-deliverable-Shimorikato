# Trade-Driven Option Fair Pricing with Aggregated WebSocket Delivery

## 1. Executive Summary

This report documents the design and implementation of a trade-driven option pricing update pipeline in the exchange backend. The implemented flow addresses three core requirements:

1. Consume TradeExecuted stream events and dynamically recompute option fair prices.
2. Throttle and aggregate updates (per symbol, per 100 milliseconds) to reduce user interface churn and infrastructure load.
3. Publish OptionPriceUpdated events over the WebSocket stream for real-time client consumption.

The resulting architecture favors low-latency responsiveness while preserving backend stability under bursty market activity. Instead of triggering immediate full-chain broadcasts for every trade, the system computes updated pricing signals and coalesces intermediate updates into deterministic 100 ms windows. This significantly lowers outbound event frequency while retaining practical market freshness from the perspective of end users.

The implementation is centered around Java services in the exchange backend and includes dedicated logic for event ingestion, option fair-value calculation, symbol-scoped aggregation, and websocket emission.

---

## 2. Problem Statement and Motivation

### 2.1 Market behavior and update pressure

In active markets, trade events can arrive at very high rates, especially for highly liquid underlyings. If every TradeExecuted event directly drives a full option-chain recalculation and immediate WebSocket push, the result is often:

- Excessive message volume on the websocket channel.
- High serialization and network overhead.
- Frequent front-end re-renders that provide little additional user value.
- Increased tail latency due to resource contention.

The system therefore needs a way to stay near real-time while preventing avoidable update amplification.

### 2.2 Functional requirements

The required behavior is:

- Use TradeExecuted as the trigger source for option repricing.
- Recompute option fair prices dynamically, reflecting underlying trade movements.
- Aggregate updates on a per-symbol basis and flush at fixed intervals (100 ms).
- Publish consolidated OptionPriceUpdated payloads through websocket for frontend consumption.

### 2.3 Non-functional expectations

Beyond functional correctness, the system should maintain:

- Deterministic and predictable update cadence.
- Low operational overhead during load bursts.
- Thread-safe behavior with no race conditions between ingestion and flush paths.
- Clear extension points for future telemetry, model upgrades, or risk controls.

---

## 3. Implementation Scope and Main Java Artifacts

The implementation is captured in the following Java files prepared in the submission folder:

- exchange-back-end/src/main/java/com/helesto/service/TradeDrivenPricingService.java
- exchange-back-end/src/main/java/com/helesto/socket/WebSocketAggregator.java
- exchange-back-end/src/main/java/com/helesto/service/BlackScholesPricingService.java
- exchange-back-end/src/main/java/com/helesto/service/TradeService.java
- exchange-back-end/src/main/java/com/helesto/service/ReferenceDataService.java
- exchange-back-end/src/main/java/com/helesto/model/TradeEntity.java

### 3.1 Responsibility summary

TradeDrivenPricingService is the orchestration core. It subscribes to trade events, computes or triggers fair-value updates, aggregates symbol-level updates, and flushes to websocket broadcaster.

WebSocketAggregator is the distribution layer. It emits update messages to subscribed clients in a normalized event format.

BlackScholesPricingService encapsulates option valuation logic, including fair-value and related numerical computations.

TradeService is the event producer/dispatcher context for trade stream notifications.

ReferenceDataService supplies supporting market/reference data used during repricing.

TradeEntity represents trade payload structure used in stream handling.

---

## 4. End-to-End Processing Flow

### 4.1 Step 1: Trade event ingestion

A TradeExecuted event enters through the trade service path and is received by the pricing orchestration service. Validation and mapping happen first:

- Identify symbol and instrument context.
- Verify trade price and quantity sanity.
- Establish whether this trade impacts current option valuation state.

Invalid or irrelevant events are ignored early to avoid unnecessary compute cost.

### 4.2 Step 2: Dynamic fair-price recomputation

On valid events, the service recomputes fair prices for impacted option instruments (for the affected underlying symbol and relevant strikes/expiries).

The model service uses Black-Scholes style fair-value computation with practical safeguards:

- Stable handling near expiry.
- Protection against non-positive volatility inputs.
- Numerical checks to prevent propagation of NaN or infinite values.

The repricing output is converted into an OptionPriceUpdated-style payload for downstream emission.

### 4.3 Step 3: Per-symbol throttling and aggregation

Instead of immediate broadcast, updates are staged in a per-symbol aggregation structure.

A periodic scheduler flushes every 100 ms:

- For each symbol, latest meaningful state is selected.
- Intermediate updates inside the same window are coalesced.
- One consolidated update per symbol is emitted per flush cycle.

This ensures bounded output rate and materially reduces front-end churn.

### 4.4 Step 4: WebSocket publication

Flushed updates are passed to the WebSocketAggregator, serialized in the configured event envelope, and pushed to active subscribers.

Result: Clients receive frequent but controlled updates that reflect near-current fair prices without being overwhelmed by micro-burst traffic.

---

## 5. Pricing Model Notes (Black-Scholes Context)

The system uses Black-Scholes style fair-value logic as the pricing baseline. Conceptually:

- Inputs include spot, strike, time-to-expiry, risk-free rate, and volatility.
- Call and put values are derived from d1 and d2 terms.

Call value:

C = S _ N(d1) - K _ exp(-rT) \* N(d2)

Put value:

P = K _ exp(-rT) _ N(-d2) - S \* N(-d1)

Where:

- S is spot price.
- K is strike.
- r is risk-free rate.
- T is time to expiry.
- sigma is volatility.
- N(.) is standard normal cumulative distribution.

### 5.1 Practical implementation considerations

Production-grade option valuation must enforce boundary behavior:

- Very small T can destabilize terms if not clamped.
- Very small sigma can cause division instability.
- Extreme input values require sanity ranges.

These defensive checks are important viva talking points because they differentiate textbook formulas from robust implementation.

---

## 6. Aggregation Design and Tradeoffs

### 6.1 Why aggregation is necessary

If every trade generates immediate websocket push, outbound volume can be proportional to trade rate, which is not scalable under stress. Aggregation shifts cost from event-per-trade to event-per-window.

### 6.2 Why per-symbol granularity

Per-symbol aggregation prevents hot symbols from starving low-volume symbols and preserves update locality:

- Symbol A burst does not block Symbol B updates.
- Coalescing remains semantically meaningful because option chains are symbol-scoped.

### 6.3 Why 100 ms window

100 ms is a practical engineering compromise:

- Low enough to appear real-time to users.
- High enough to cut update storms significantly.
- Provides deterministic upper-bound broadcast cadence around 10 Hz per symbol.

### 6.4 Information tradeoff

Aggregation intentionally drops intermediate microstates within a window. This is acceptable for UI display channels focused on current fair value rather than full tick-by-tick audit replay.

---

## 7. Concurrency, Thread Safety, and Reliability

### 7.1 Concurrency model

Two asynchronous flows coexist:

- Event ingestion thread(s) receiving trades.
- Scheduled flush thread emitting consolidated updates.

Shared state (aggregation cache/buffer) must be thread-safe to prevent lost updates or inconsistent snapshots.

### 7.2 Reliability safeguards

Implementation considerations include:

- Fast failure on malformed input.
- Exception isolation so one symbol failure does not halt flush loop.
- Non-blocking behavior in critical paths.
- Monitoring counters for dropped/invalid events and flush throughput.

### 7.3 Failure scenarios

Typical risks and handling:

- Bursty traffic: coalescing controls outbound pressure.
- Invalid model inputs: sanitized or skipped.
- WebSocket client churn: broadcaster should handle disconnects gracefully.
- Scheduler delays: next cycle catches latest symbol state, preserving eventual freshness.

---

## 8. Performance and Scalability Impact

### 8.1 Computational efficiency

Direct event-per-trade broadcasting scales poorly under high-frequency conditions. Windowed aggregation reduces repeated serialization and repeated frontend diff/render cycles.

### 8.2 Network and UI behavior

Expected improvements:

- Reduced websocket frame count.
- Lower frontend reconciliation overhead.
- Smoother visible chart/table behavior.
- Better tail latency during market bursts.

### 8.3 Operational stability

Because output cadence is bounded, capacity planning becomes more predictable. This also simplifies alert thresholding for websocket throughput and backend CPU utilization.

---

## 9. Validation Approach

Although this report focuses on implementation design, the following validation strategy supports confidence:

1. Unit tests for pricing logic across normal and edge input sets.
2. Aggregation tests verifying one update per symbol per window under burst events.
3. Concurrency tests for no data races between ingestion and scheduled flush.
4. Integration tests validating end-to-end path from TradeExecuted to websocket payload.
5. Load tests to confirm output-rate reduction and stable latency.

### 9.1 Key acceptance indicators

- Fair values update after relevant trades.
- Websocket publication occurs via OptionPriceUpdated stream path.
- Message count under burst is significantly lower than raw trade count.
- UI remains responsive and avoids excessive redraw churn.

---

## 10. Design Extensions and Future Improvements

The current approach is a robust baseline. Future enhancements can include:

- Adaptive window sizing based on symbol volatility or current load.
- Per-client subscription filtering (strike range, expiry buckets).
- Hybrid model path with implied volatility recalibration.
- Structured telemetry for model drift and distribution lag.
- Optional delta-only payload mode to reduce wire size further.

These are additive enhancements and do not change the core architecture delivered for current requirements.

---

## 11. Conclusion

The implemented Java backend design fulfills all required objectives:

- TradeExecuted events are consumed as the trigger for dynamic repricing.
- Option fair values are recomputed in near real time using model-driven logic.
- Updates are throttled and aggregated per symbol every 100 ms, reducing churn while preserving practical freshness.
- Consolidated OptionPriceUpdated messages are broadcast over websocket for live client updates.

From an engineering perspective, this solution balances responsiveness, correctness, and scalability. It demonstrates an effective event-driven market data pipeline suitable for exchange-style UI workloads where low latency is important but uncontrolled update fan-out is not acceptable.
