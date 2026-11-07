# Engineering Constitution

**Version:** 1.0\
**Status:** Active

## Purpose

This constitution defines the default engineering philosophy for every
project.

## Core Principles

### 1. Simplicity First

Always choose the simplest solution that satisfies the current
requirement.

Priority:

    Reuse existing mechanism
    >
    Small extension
    >
    New abstraction
    >
    New subsystem

### 2. Avoid Unnecessary Complexity

Before proposing any design, always ask:

-   Can this reuse an existing mechanism?
-   Is this solving more than today's problem?
-   Does this introduce a framework for only one use case?
-   Can ownership remain where it already exists?

If yes, simplify first.

### 3. Respect Existing Ownership

Reuse existing ownership whenever possible.

Never duplicate persistence, transport, retry, relay, ordering,
lifecycle, history or state ownership without compelling architectural
justification.

### 4. Architecture Before Code

    Read vision
    ↓
    Read roadmap
    ↓
    Read contracts
    ↓
    Read source
    ↓
    Review ownership
    ↓
    Find smallest solution
    ↓
    Discuss changes if needed
    ↓
    Lock contract
    ↓
    Independent review
    ↓
    Implement

### 5. No Silent Architecture Changes

If implementation requires architecture, UX, ownership, workflow or
capability changes, discuss and obtain agreement first.

### 6. Prefer Product Simplification

Do not compensate for complicated product rules by adding architectural
complexity.

When appropriate, simplify the product rule instead.

### 7. No Over-Engineering

Do not introduce:

-   frameworks
-   registries
-   plugin systems
-   duplicate repositories
-   duplicate persistence
-   duplicate lifecycles
-   duplicate state machines

unless multiple concrete use cases justify them.

### 8. Stability First

Decision priority:

    Stability
    >
    Correctness
    >
    Maintainability
    >
    Performance

### 9. Contracts Are Authoritative

Locked contracts are the implementation authority until formally
revised.

### 10. Independent Review

Every significant contract should undergo an independent architecture
review before implementation.

### 11. Reuse Before Reinvention

Extend existing mechanisms before creating new ones.

Every new abstraction must justify why existing architecture is
insufficient.

### 12. Minimize Surface Area

Every new API, interface, state, lifecycle, database column or
background worker must have a clear architectural justification.

### 13. Architecture Over Passing Tests

Passing tests does not prove good architecture.

Prefer:

-   correct ownership
-   correct boundaries
-   architectural consistency

over temporary convenience.

## Guiding Principle

> Build the smallest system that correctly solves today's problem while
> preserving tomorrow's maintainability.

When in doubt:

-   simplify;
-   reuse;
-   preserve ownership;
-   discuss before redesign.
