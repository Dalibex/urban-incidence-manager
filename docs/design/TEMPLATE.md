# DD-[NNN] — [Title of the change]

> **Instructions:** Replace the fields `[in brackets]`; remove the guidance that is not needed. Keep the alternatives section even if the solution is simple. Tell proposals, verified facts and open questions apart. Do not state that a decision is approved without a real review.

| Field | Value |
| --- | --- |
| Status | Draft / In review / Accepted / Superseded / Rejected |
| Authors | [Team and owners] |
| Date of last revision | [YYYY-MM-DD] |
| Iteration | [Number and goal] |
| Reviewers | [People or roles] |
| Links | [Issue, PR, ADR, C4, OpenAPI, tests] |

## 1. Executive summary

[In 4–6 lines: the problem, the proposed solution, the main reason and the main risk.]

## 2. Context and problem

- **Verifiable current situation:** [Existing architecture or behaviour, and its sources].
- **Problem:** [Observable consequence for users, operation or the team].
- **Motivation:** [Why now; dependency on the iteration].

## 3. Goals, non-goals and scope

**Goals**

- G1. [Verifiable functional or technical result].

**Non-goals**

- N1. [What will expressly NOT be addressed].

**In scope:** [Affected services, contracts and data].
**Out of scope:** [Excluded services or migrations].

## 4. Requirements and architectural drivers

| ID | Type | Need or scenario | Acceptance criterion and evidence | Priority |
| --- | --- | --- | --- | --- |
| FR-... | Functional | [Behaviour] | [Observable test] | [High/Medium/Low] |
| QR-... | Quality | [Load, failure or condition] | [Metric + threshold + tool] | [...] |
| CR-... | Constraint | [Technology, deadline, budget, compliance] | [How it is checked] | [...] |

> For quality requirements, describe the scenario + conditions + metric + threshold + validation method. Thresholds must come from the assignment, a negotiation or a measurement; do not invent them as facts.

## 5. Current architecture and dependencies

- **C4 context and containers:** [Link to the current diagram].
- **Current flow:** [Summary].
- **Dependencies and constraints:** [Internal and external services].

## 6. Alternatives considered

| Alternative | Description | Advantages | Drawbacks and risks | Available evidence |
| --- | --- | --- | --- | --- |
| A | [...] | [...] | [...] | [...] |
| B | [...] | [...] | [...] | [...] |
| C (optional) | [...] | [...] | [...] | [...] |

**Comparison criteria:** [Complexity, cost, availability, latency, evolution, security...].
**Proposed choice and justification:** [Why it beats the other options on the priority drivers].

## 7. Proposed design

### 7.1. Overview and C4 diagrams

- **Context (L1):** [Link or "no changes"].
- **Containers (L2):** [Link and differences].
- **Components (L3):** [Only if it helps].
- **Sequences or event flow:** [Link or description].

### 7.2. Interfaces and contracts

[Endpoints, events, versions, JSON schema, errors, idempotency, compatibility. Reference OpenAPI or AsyncAPI where it applies.]

### 7.3. Data model and management

[Affected entities, ownership, consistency, migrations, retention, recovery, privacy.]

### 7.4. Failures, concurrency and resilience

[Timeouts, retries, duplicates, order, circuit breakers, degradation, recovery.]

### 7.5. Security

[Identity, authorization, secrets, threats, auditing, sensitive data.]

### 7.6. Operation and deployment

[Topology, AWS if it applies, IaC, observability, scaling, rollback, costs.]

## 8. Decisions and trade-offs

| Proposed decision | Benefit | Cost or consequence | Needs an ADR? |
| --- | --- | --- | --- |
| [Example] | [...] | [...] | Yes / No |

[Link the accepted ADRs, telling them apart from open proposals.]

## 9. Implementation and migration plan

1. [Incremental change and demonstrable delivery].
2. [Tests and contract change].
3. [Controlled deployment, feature flags or transition if it applies].
4. [Rollback and clean-up plan].

**Dependencies:** [...]. **Impact on earlier versions:** [...].

## 10. Verification and evaluation strategy

| Requirement | Test or experiment | Environment and data | Metric or threshold | Expected evidence |
| --- | --- | --- | --- | --- |
| QR-... | [k6, integration, injected failure...] | [...] | [...] | [Report, traces, logs, CI test] |

Explain what can only be validated in a real cloud, as opposed to locally or simulated.

## 11. Risks, hypotheses and open questions

| ID | Type | Description | Impact | Action and owner |
| --- | --- | --- | --- | --- |
| H-1 | Hypothesis | [...] | [...] | [Experiment] |
| R-1 | Risk | [...] | [...] | [Mitigation] |
| Q-1 | Question | [...] | [...] | [Pending decision] |

## 12. Review and result

- **Review comments:** [Links or a summary of the relevant objections].
- **Resolution:** [Accepted / pending / rejected].
- **Recorded decisions:** [ADR]. Optional: fill it in once the ADRs exist; each ADR also names this document.
- **Differences observed after implementing:** [What changed from the design, why, and where it was documented].

## Approval checklist

- [ ] Problem and scope delimited.
- [ ] Drivers and constraints documented, without attributing to the assignment requirements it does not have.
- [ ] At least two alternatives compared, or a justification of why there are no viable alternatives.
- [ ] Diagrams, data and contracts consistent.
- [ ] Failures, security, operation and costs considered in proportion to the change.
- [ ] Verifiable acceptance criteria and a reproducible test plan.
- [ ] Risks and open questions explicit.
- [ ] Technical review done; ADRs linked where they apply.
