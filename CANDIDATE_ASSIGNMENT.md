# Senior Backend Engineer & System Architect — Practical Coding Challenge

## System Scenario: Autonomous Storefront Node in a Distributed Fleet

### 1. Executive Context
Our organization operates hundreds of high-volume retail and dining outlets across diverse facilities (hospitals, university campuses, airports, commercial hubs). 

To ensure continuous business operations regardless of external network disruptions, each physical store runs an **autonomous, on-premise application node** connected to local terminals (cashier POS, self-order kiosks, mobile ordering tablets, kitchen display systems). 

The edge node must operate with **zero dependency on wide-area internet connectivity**. If the connection to Central HQ is severed for hours or days, store operations must continue uninterrupted. When connectivity is restored, transactional data synchronizes asynchronously with Central HQ.

You are tasked with designing and implementing the **core ordering, inventory, and kitchen dispatch engine** for this edge node.

---

### 2. Operational Challenges & System Requirements

Your implementation must address five real-world edge operational challenges:

#### Scenario A: High-Contention Rush & Inventory Depletion
During peak meal hours, 30 to 50 local terminals simultaneously process checkout requests for limited-stock items (e.g., pre-packaged fresh meal boxes or bottled specialty items).
* **Requirements & Invariants**:
  * High-concurrency throughput with sub-second response times.
  * **Zero overselling under any circumstance**: If only 10 units remain in inventory and 50 terminals attempt to purchase simultaneously, exactly 10 must succeed and 40 must fail cleanly with a domain-appropriate error.
  * The system must avoid unhandled database deadlocks and thread pool starvation under contention spikes.

#### Scenario B: Localized Fiscal Sequence Numbering
Government and fiscal compliance requires that every customer order issued at a branch carries a localized, gap-free, sequential document number (e.g., resetting per branch, per calendar day: `ORD-{BRANCH_CODE}-{YYYYMMDD}-{0001}`).
* **Requirements & Invariants**:
  * Because the node operates offline, sequence generation cannot depend on an external central authority or cloud service.
  * Under high concurrent submission across dozens of local terminals, document numbers must remain strictly unique, chronologically ordered, and gap-free without causing a bottleneck that serializes entire transactions.

#### Scenario C: Unreliable Local Wireless & Network Retries
Edge Wi-Fi and mobile networks in large commercial facilities experience packet drops and latency spikes. Handheld terminals and kiosks automatically retry timed-out requests.
* **Requirements & Invariants**:
  * If a client times out waiting for an HTTP response after the server has already committed the transaction, any subsequent retry from that terminal must:
    1. Return the previously committed order outcome rather than erroring or re-processing.
    2. **Never** double-decrement inventory.
    3. **Never** consume or increment a secondary fiscal sequence number.
  * Under high concurrency, duplicate requests arriving within milliseconds of each other must not bypass this protection.

#### Scenario D: Event-Driven Kitchen & Preparation Dispatch
When an order containing prepared food is finalized, downstream food preparation stations (Kitchen Display System / KDS) must be notified immediately.
* **Requirements & Invariants**:
  * Kitchen ticket creation is an independent operational concern. A transient failure, delay, or slow consumer in the kitchen subsystem must **never abort, rollback, or delay** the customer's checkout payment.
  * **Reliability Guarantee**: Once an order transaction commits, the notification to the kitchen must be guaranteed never to be lost—even if the application node crashes, reboots, or loses power immediately after the order commits.
  * The state across boundaries must remain strictly consistent, preventing phantom orders or unnotified kitchen tickets.

#### Scenario E: Financial Precision & Multi-Currency Safety
Outlets operate across different regions, currencies, and tax schemes.
* **Requirements & Invariants**:
  * The domain model must be strictly immune to floating-point rounding errors and mixed-currency arithmetic bugs across line items, subtotals, tax rules, and payments.

---

### 3. Architecture & Design Principles

We evaluate candidates on software craftsmanship, separation of concerns, and clean architectural boundaries.

1. **Separation of Concerns**:
   * Keep business logic completely decoupled from infrastructure, frameworks, database drivers, and web delivery mechanisms.
   * Model business invariants inside cohesive domain structures.
2. **Persistence & Data Modeling**:
   * Entities must have globally unique identifiers suitable for eventual synchronization across thousands of independent edge nodes without primary key collisions at Central HQ.
3. **Database Migrations**:
   * Provide automated database migration scripts (e.g., Flyway or Liquibase). Do not rely on ORM auto-generation in production mode.

---

### 4. Candidate Deliverables

1. **Source Code**:
   * A clean, runnable project in **Java 21 / Spring Boot 3+** (Maven or Gradle).
   * Relational database persistence (PostgreSQL via Testcontainers or an equivalent setup).
2. **Automated Verification Test Suite**:
   * **Concurrency Stress Test**: An automated integration test simulating multiple parallel threads (30–50 threads) competing for a constrained stock pool. The test must programmatically prove:
     * Zero overselling.
     * Sequence uniqueness and monotonicity.
     * Safe handling of duplicate submissions and retries.
   * **Domain Unit Tests**: Fast tests validating business invariants without loading a Spring or database context.
3. **Architecture & Trade-Offs Document (`DESIGN.md`)**:
   A concise (1–2 page) markdown document detailing:
   * **Architectural Boundaries**: How you organized your code and why.
   * **Concurrency Strategy**: The concurrency control mechanism you chose (e.g., pessimistic locking, optimistic locking, atomic SQL, actor/queue) and the specific trade-offs considered.
   * **Downstream Reliability**: How your design guarantees event notification to downstream stations under failure and restart scenarios.
   * **Network Retry Handling**: How your system identifies and safely processes duplicate client submissions.
   * **Distributed Sync Readiness**: How your entity identifiers and state models support eventual bidirectional synchronization with Central HQ without data collisions.

---

### 5. Evaluation Criteria Summary

Your submission will be evaluated on:
* **Domain Modeling**: Expressiveness, immutability, avoidance of framework pollution in business logic.
* **Concurrency Handling**: Correctness, thread safety, deadlock prevention, throughput.
* **Distributed Systems Resilience**: Retry handling, offline autonomy, at-least-once event delivery guarantees.
* **Engineering Rigor**: Code readability, automated tests, clean database migrations, and architectural rationale.
