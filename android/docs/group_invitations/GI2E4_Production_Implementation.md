# GI2E4 — Private Invitation Send Pipeline Production Implementation

**Status:** IMPLEMENTED — AWAITING USER VERIFICATION

## Production changes

- Added `NearbyPrivateGroupInvitationSendPipeline`.
- Added semantic-only private request and typed send results.
- Constructed canonical private-inner payload through `GroupInvitationProtocol`.
- Built the exact GI2D authenticated context from repository-issued preparation.
- Converted the frozen pinned Nearby identity into GI2D's validated recipient identity.
- Invoked GI2D encryption exactly once per pipeline invocation.
- Zeroed the caller-owned plaintext byte array immediately after GI2D returns.
- Constructed the canonical private envelope from GI2D primitive output.
- Passed canonical outer body and canonical sender-local inner body to prepared insertion.
- Submitted only the persisted entity returned by repository insertion.
- Extracted GI2E3 transport submission into one shared internal bridge used by both pipelines.

## Focused JVM tests added

- repository preparation and audience binding;
- exact immutable crypto context;
- one encryption invocation;
- canonical inner source persistence;
- canonical outer envelope construction;
- caller-owned plaintext cleanup;
- persisted-entity-only transport submission;
- transport failure retained for retry without a second encryption;
- crypto failure stops before insertion and transport;
- invalid semantic input stops before consuming repository authority;
- request surface exposes no authoritative or cryptographic fields.

## Verification state

Gradle tests were not executed in the artifact environment because the Gradle distribution was not
cached and outbound network access to `services.gradle.org` was unavailable. The user must run the
focused JVM and existing regression suites in the project environment.

GI2E4 is not CLOSED or LOCKED.
