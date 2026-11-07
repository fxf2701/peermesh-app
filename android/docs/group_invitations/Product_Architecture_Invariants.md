# Product_Architecture_Invariants

**Status:** LOCKED\
**Applies to:** All current and future Group Invitation milestones.

This document is the architectural "constitution" for the Group
Invitation feature.

Every architecture review, contract review, code review and
implementation must be checked against these invariants.

If a proposed design violates any invariant, it must be rejected unless
the Product Specification is explicitly revised.

------------------------------------------------------------------------

# Invariant 1 --- Group Invitation is a Structured Nearby Chat Message

A Group Invitation **is**:

``` text
Structured Nearby Chat message
```

It is **not**:

-   a transport protocol;
-   a transport kind;
-   an event;
-   a batch;
-   an independent subsystem.

------------------------------------------------------------------------

# Invariant 2 --- Nearby Chat Owns Transport

Nearby Chat owns:

-   transport;
-   relay;
-   retry;
-   ordering;
-   deduplication;
-   persistence;
-   chat history.

Group Invitation must reuse these mechanisms.

------------------------------------------------------------------------

# Invariant 3 --- Invitation Card Is Presentation Only

Invitation Card is:

``` text
Structured message
    ↓
Parser
    ↓
UI renderer
```

It is never:

-   a domain aggregate;
-   a repository;
-   a database entity;
-   a lifecycle object.

------------------------------------------------------------------------

# Invariant 4 --- No Invitation Subsystem

The architecture must not introduce:

-   Invitation repository;
-   Invitation table;
-   Invitation state table;
-   Invitation history subsystem;
-   Invitation template subsystem;
-   Invitation transport family;
-   Invitation retry/relay pipeline.

------------------------------------------------------------------------

# Invariant 5 --- Exactly Two Invitation Rules

Only these business rules exist:

``` text
1. One public invitation per sender per room.

2. One private invitation
   per sender-recipient pair per room.
```

No additional card-management rules may be added without revising the
Product Specification.

------------------------------------------------------------------------

# Invariant 6 --- Security Is Independent

Recipient identity, key pinning and encryption are security foundations.

They must not know about:

-   Invitation Card;
-   Join button;
-   group name;
-   description;
-   invitation send limits;
-   UI.

------------------------------------------------------------------------

# Invariant 7 --- Invitation Depends on Security

Invitation sending may consume:

-   recipient identity;
-   key pinning;
-   encryption.

Security layers must never depend on Invitation.

Dependency direction:

``` text
Security
    ↑
Invitation
```

------------------------------------------------------------------------

# Invariant 8 --- Minimal Persistence

Reuse existing persistence whenever possible.

New persistence is allowed only when:

-   it is generic;
-   it has a demonstrated second use case; or
-   it is required for correctness.

Avoid feature-specific tables.

------------------------------------------------------------------------

# Invariant 9 --- Additive Compatibility

Protocol evolution must be:

-   additive;
-   backward compatible;
-   forward compatible.

Old clients must ignore unknown fields safely.

New clients must tolerate missing optional fields.

------------------------------------------------------------------------

# Invariant 10 --- Product Before Optimization

Priority order:

``` text
Stability
>
Correctness
>
Maintainability
>
Performance
```

Do not redesign or generalize without a concrete need.

------------------------------------------------------------------------

# Architecture Review Gate

Before approving any milestone, answer:

-   Does this create a new subsystem?
-   Does this create a new persistence model?
-   Does this duplicate Nearby Chat ownership?
-   Does Security depend on Invitation?
-   Does Invitation change Security behavior?
-   Does it add invitation-management rules beyond the two authorized
    rules?
-   Is it backward compatible?
-   Can the same goal be achieved with the existing architecture?

If any answer indicates a violation of these invariants, stop the review
and justify a Product Specification change before implementation.

------------------------------------------------------------------------

# Authority

Document precedence:

``` text
Product_Architecture_Invariants.md
        ↓
Group_Invitation_Spec_v2.md
        ↓
GI1R Architecture Realignment
        ↓
Implementation Roadmap
        ↓
Milestone Contracts
        ↓
Implementation
```
