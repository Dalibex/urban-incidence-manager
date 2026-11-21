# 📐 Design Documents

What a design document is, how the client validates it, and the index of the design documents of
UrbanPulse.

> [!NOTE]
> For the client (the lecturers), the team and agents. A design document proposes; the decisions it
> leads to are recorded in the [ADRs](../adr/), and what is built is in
> [Architecture](../architecture.md).

## 📑 Contents

- **[What a design document is](#-what-a-design-document-is)**: what it is for, and how it differs from an ADR.
- **[Status](#-status)**: the status of a design document and what moves it from one to the next.
- **[How a design is agreed and validated](#-how-a-design-is-agreed-and-validated)**: the team's decision, the review on GitHub and the client's validation.
- **[How to write one](#-how-to-write-one)**: the template, the number and the issue.
- **[Index](#-index)**: every design document and its status.

## 💡 What a design document is

The final delivery asks that every design proposal is written in a design document and validated
by the client before it is built. A design document (DD) reasons about one change: the problem,
the requirements and drivers behind it, at least two alternatives compared, the proposed design,
its risks and how it will be verified.

It is not an ADR. The design document comes first: it compares and proposes, and the client
reviews it. Once it is accepted, each decision it takes is recorded in its own ADR in
[`docs/adr/`](../adr/). Which ADRs it leads to, or replaces, is not known while it is written, so
the link goes the other way: each ADR names the design document it comes from, in its
*Design document* line. A DD can lead to several ADRs, or to none if it decides nothing costly to
change.

## 🔄 Status

The status is the first row of the table at the top of each document.

| Status | Meaning | Moves on when |
| --- | --- | --- |
| Draft | Being written and discussed in the team; nothing in it is a decision. | The team agrees on the proposal and its pull request is merged. |
| In review | Agreed by the team, waiting for the client. | The client gives its result. |
| Accepted | Validated by the client; its ADRs can be written and the change built. | A later DD replaces it: Superseded. |
| Superseded | Replaced by another DD, which it names. | Final. |
| Rejected | The client or the team did not accept it; kept for its reasoning. | Final. |

## ✅ How a design is agreed and validated

1. The team discusses the proposal and agrees on it. Who takes part is the team's choice: all of
   it, or the people it names to speak with the client.
2. The pull request of the document is reviewed like any other
   ([Review and merge](../../CONTRIBUTING.md#review-and-merge)): it checks that the document is
   complete and consistent, and merges it with the status *In review*. That approval does not
   accept the design.
3. The team presents it to the client.
4. Section 12, *Review and result*, records the date, the client's comments and the result, and
   the status changes to *Accepted* or *Rejected*, in a pull request. Its *Recorded decisions* line
   is optional and filled in once the ADRs exist.
5. Once it is built, section 12 also records what changed from the design and why.

Do not mark a document *Accepted*, nor build what it proposes, without the client's review.

## 📝 How to write one

1. Copy [`TEMPLATE.md`](TEMPLATE.md), the course's template, to `DD-NNN-short-title.md` with the
   next free number, and add its row to the [index](#-index).
2. Follow every section that applies to the change. Every detail need not be filled in: what the
   client asks for is the reasoning, the problems of each requirement and their possible solutions.
3. Tell verified facts, with their source (code, ADR, measurement), apart from proposals and open
   questions.
4. Each document has its `[Docs]` issue, which the pull request of the document closes.

## 📚 Index

| DD | Title | Status | Issue |
| --- | --- | --- | --- |
| DD-001 | Target architecture, resilience and contracts between services | Planned | #328 |
| DD-002 | Citizen reports and normalized incidents | Planned | #334 |
| DD-003 | Evidence in an external image service | Planned | #342 |
| DD-004 | Context service: the open data, the weather and how they are kept up to date | Planned | #347 |
| DD-005 | Audit service, auditor role and audit dashboard | Planned | #354 |
| DD-006 | Observability and SLIs | Planned | #363 |

A document that is not written yet is *Planned* under its issue; its pull request links the title
to the file and sets its status.
