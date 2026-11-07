# M3C4 compile-oriented skeleton

This skeleton adds only presentation composition and Android integration points.

Included:

- a stateless projection bundle over accepted M3C1/M3C2/M3C3 projectors;
- a lossless adapter from accepted M3A shell content to existing projector input;
- a pure composition factory with no database, repository, controller or submission dependency;
- Activity lifecycle fields and reprojection hooks;
- an explicit TODO renderer for the later Codex implementation.

Intentionally absent:

- final Android rendering and strings/layout changes;
- fresh-confirmation CTA decision/rendering;
- business logic or semantic changes;
- new controller/state owner;
- repository/DAO/persistence/schema changes;
- submission or timeline-controller changes.

Verification:

- `git diff --check`: PASS.
- Gradle compile could not run in the artifact environment because Gradle 8.7 was not cached and outbound network access was unavailable.
