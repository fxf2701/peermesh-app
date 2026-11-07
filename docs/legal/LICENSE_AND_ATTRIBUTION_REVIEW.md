# License Metadata and Third-Party Attribution — Publication Review

**Status: OPEN / pre-publication review (2026-11-07).** This is a documentation record, not a legal clearance or a statement that all assets have been audited.

## Intended project license

The repository root [LICENSE](../../LICENSE) contains Apache License, Version 2.0, and the root [README](../../README.md) describes PeerMesh as Apache-2.0 licensed. The intended license for project-owned source and documentation is therefore **Apache-2.0**, subject to confirmation of ownership and any third-party exceptions.

**Observed metadata discrepancy:** `server/registry/package.json` currently declares `"license": "UNLICENSED"` and `"private": true`. `UNLICENSED` conflicts with the intended repository-wide grant; `private: true` is a separate npm publishing safeguard and does not itself prohibit open-source licensing. This documentation-only phase does **not** change package metadata. **Resolve and verify the discrepancy before public publication.**

The relay package does not currently declare a `license` field; inspect the final package manifests as part of the same review.

## Attribution and dependency review still required

Before publication, inventory project-shipped icons, artwork, map assets/data, fonts, embedded libraries, and copied/adapted materials. For each item, record its origin, rights holder, exact license and version, source URL, any changes, and required notices. Do not infer that an asset is covered by Apache-2.0 merely because it is inside the repository.

The audit noted icon and OpenStreetMap attribution in some UI/source locations but did not establish complete third-party attribution or an exact Creative Commons version for every asset. This review remains **unverified**. A separate `NOTICE` file is not automatically required for every Apache-2.0 project; determine whether one is necessary from actual attribution obligations.

## Publication gate

- [ ] Confirm rights to all project-owned material intended for release.
- [ ] Reconcile registry and relay package license metadata with the intended scope.
- [ ] Record exact third-party licenses, attribution, and any redistribution obligations.
- [ ] Check whether a `NOTICE` file or bundled license copies are required.
- [ ] Verify public README, contribution guidance, and package metadata are consistent.

Do not mark this gate complete solely because a root `LICENSE` file exists.
