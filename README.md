# PeerMesh App

**Offline-first group communication and shared situational awareness, powered primarily by Bluetooth Low Energy (BLE) mesh — no account required.**

**Official website:** https://peermesh.app/  
**License:** [Apache-2.0](LICENSE)  
**Project status:** Experimental / Research — source code only

> **Purpose:** Help nearby groups communicate, coordinate, and share useful information, including when conventional networks are unavailable.
>
> **Safety:** Experimental software; not a certified emergency communication system. **Known security limitations include forgeable batch MACs, insufficient cryptographic proof of owner actions, and incomplete group-key revocation.** Do not use this baseline for sensitive or adversarial communication. See [Known Security Limitations](docs/security/KNOWN_SECURITY_LIMITATIONS.md).

> [!IMPORTANT]
> **Research project — no public app release**
> PeerMesh App is currently published as source code for research, learning, independent security review, and community contributions. **No prebuilt APK, iOS app, or production-ready binary is distributed** through GitHub or the official website. Developers may build and test the project from source at their own discretion. Do not rely on it as the sole communication method in emergencies.

---

## 1. Overview

PeerMesh App is an **offline-first, local-first application for group communication and shared situational awareness**. It is designed for outdoor activities, community coordination, nearby conversations, and disruptions to conventional networks. Participants can share messages and map-based information to help their group stay informed.

The primary communication mechanism is **BLE-based peer-to-peer mesh networking**: nearby devices exchange and forward information without Internet access, cellular service, user registration, or a centralized server. **No account registration is required to use PeerMesh App.**

**Cloud Sync is optional and secondary.** It can supplement BLE mesh communication or act as a fallback when BLE is unavailable and network connectivity exists. It must respect the user’s explicit Cloud Sync setting: loss of Internet connectivity is not the same as disabling Cloud Sync, and Cloud Sync must not be automatically re-enabled after the user turns it off.

PeerMesh is general-purpose research technology, not exclusively a disaster-response product. Explore the project at [peermesh.app](https://peermesh.app/).

---

## 2. Core Principles

- **Offline-first** – Works without internet access
- **BLE mesh first** – Peer-to-peer BLE communication and forwarding are the primary transport
- **No account required** – No registration or centralized account is needed to use the app
- **Local-first operation** – BLE mesh does not depend on a central server; optional Cloud Sync and Internet relay paths are secondary and user-controlled
- **Ephemeral data** – Information expires automatically
- **Minimal assumptions** – Best-effort delivery, no real-time guarantees

---

## 3. Intended Use Cases

Example use cases include:

- Hiking, camping, cycling, and outdoor group activities
- Sharing location-related observations and map markers within a group
- Community coordination and temporary events
- Nearby conversations without requiring account registration
- Network outages, natural disasters, and infrastructure-poor environments
- Research and experimentation in decentralized communication

The project **does not promote or coordinate violence, illegal activity, or harm**.

---

## 4. Research Areas and Development Status

> **Development status:** The following list identifies implementation areas and research goals, **not a list of production-ready features**. Implementation and test coverage vary by platform and protocol flow. Refer to milestone documentation and tests for current evidence.

- **BLE mesh peer-to-peer text messaging** (primary research and implementation focus)
- Delay-tolerant networking (DTN) and store-carry-forward message delivery
- Map-based group information sharing designed for offline use
- Temporary map markers for observations, hazards, resources, or meeting areas
- Automatic data expiration (TTL)
- Group encryption, message authentication, and invitation protocols (review threat model and implementation status; some invitation flows remain under development)
- Local persistence, with **optional Cloud Sync fallback** when permitted by user settings

---

## 5. Architecture Summary

- **Local-first client design** (mobile devices act as autonomous nodes)
- **Event-based data model** (append-only events, reducer-derived state)
- **Primary transport: Bluetooth Low Energy mesh**, with delay-tolerant forwarding as a design focus
- **Secondary transport: optional Cloud Sync / Internet relay** (where implemented, available, and enabled by the user)
- **Local persistence** using SQLite

No blockchain or global consensus is required. Core local communication is designed to work without external infrastructure; optional online features may use servers.

---

## 6. Platform Research Scope

- **Android** (Kotlin): active implementation and primary development target; feature readiness varies. No prebuilt APK is currently published.
- **iOS** (Swift): referenced in cross-platform designs and documentation; no public iOS binary is distributed, and implementation or feature parity should not be assumed.

The event schema and reducer rules are intended to remain consistent across platforms, subject to OS-level constraints.

---

## 7. Security Model (Summary)

- Group-based encryption using shared secrets
- Group and batch authentication mechanisms are under research; **the shared static batch HMAC does not establish a trusted sender identity**, and privileged group actions are not independently authenticated against a malicious group member in the reviewed baseline
- **No account registration required**; protocol-level device or group identifiers may still exist for routing, security, and group communication
- No telemetry or analytics
- No centralized logs

> **Important**: This software does not guarantee anonymity, protection against malicious group members, secure removal/revocation, or safety against all threats. The current baseline has documented security weaknesses. Read [Known Security Limitations](docs/security/KNOWN_SECURITY_LIMITATIONS.md) and [Security Overview](docs/SECURITY.md) **before building or testing with real data**.

---

## 8. Limitations

BLE mesh is the primary offline communication mode. Cloud Sync is a separate optional capability and cannot replace the need for nearby BLE peers when Internet infrastructure is unavailable.

- Direct BLE connectivity is constrained by radio range, device capabilities, and environmental conditions; optional relay paths have different requirements
- Best-effort message delivery
- No guaranteed global synchronization or delivery
- Participant authentication and invitation guarantees depend on the specific protocol flow and its implementation status; do not assume every invitation flow is complete
- Device security ultimately depends on the underlying operating system

This project prioritizes **resilience and simplicity over completeness**.

---

## 9. Source Code, Build & Development

This repository contains:

- Shared event schema definitions
- Platform-specific client implementations
- Reference reducers and data models

**Distribution policy:** At this stage, PeerMesh publishes source code only through its public GitHub repository. There are no official downloadable APKs, app-store listings, or production-ready binaries. The official website provides project information rather than application downloads.

Developers interested in research or experimentation may build from source. For setup and platform-specific build instructions, see [Build & Development](docs/development/BUILD.md). Verify prerequisites and supported targets before building. Local development builds are not official public releases.

### Optional Internet Relay (Development)

Internet relay services are **not required for local BLE mesh communication**. The repository also includes optional relay components for online synchronization and federation experiments. For development and operational setup, including Docker Compose configurations, see [server/README.md](server/README.md).

Relay infrastructure is a secondary transport path and should not be presented as a prerequisite for using PeerMesh locally.

---

## 10. Responsible Use

PeerMesh supports local group communication during outdoor activities, travel in remote or wilderness areas, community assistance, and everyday conversations in nearby spaces such as cafes.

Users, contributors, and distributors should:

- Respect participants' consent when sharing their location or personal information.
- Avoid harassment, tracking others without their consent, or using the software to cause harm.
- Collect only necessary data and avoid hidden functionality.
- Describe capabilities and limitations accurately and preserve relevant notices and risk disclosures when modifying or redistributing the software.

See the [Security Overview](docs/SECURITY.md) and [Disclaimer](docs/DISCLAIMER.md) for technical limitations and practical considerations.

---

## 11. License

PeerMesh App is licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for the full terms.

Donations are optional and do not change anyone’s rights under this license.

---

## 12. Support the Project (Donations)

PeerMesh App is a free and open-source research project. If you find the research useful, you can support ongoing development, device testing, documentation, and optional infrastructure costs through **voluntary cryptocurrency donations**.

### Cryptocurrency donations

<!-- Public receiving addresses supplied by the project maintainer. Confirm network and perform a small test transfer before accepting donations. -->

| Asset | Network | Public receiving address |
| --- | --- | --- |
| USDT | BNB Smart Chain (BEP20) | `0xa50056a169B536E4fF0554749114ac90d0972047` |
| USDT | TRON (TRC20) | `TUNgNFNLRETmwoGzw6hgBNoNNQL8yjE25R` |
| BTC | Bitcoin (mainnet) | `bc1qfftrl9xx2u0l6ltcdey5tnrk4yq4zk2kws8stf` |

**Important:** These are public receiving addresses for the specified networks. The USDT (BEP20) address is the EVM address used on BNB Smart Chain, not an instruction to send via Ethereum mainnet. Verify the asset, network, and address before sending. Only send USDT on the network specified for that row. Cryptocurrency transfers are generally irreversible; sending to the wrong network may result in lost funds.

Donations are **entirely optional** and support **software development and maintenance**, **not direct disaster-relief fundraising**. Donating does not grant special access, features, governance rights, or additional rights under the Apache-2.0 license. Donations are not represented as tax-deductible charitable contributions.

If you cannot donate, reporting bugs, improving documentation, reviewing security, and contributing code are equally welcome ways to support PeerMesh.

---

## 13. Disclaimer

This source code is published for **experimental and research purposes** and provided **"as is"**, without warranty of any kind. It is not a certified emergency communication system and should not be the sole communication method relied upon in life-threatening situations.

The authors make no guarantees regarding availability, reliability, security, or suitability for any particular purpose.

Use at your own risk.

---

## 14. Contributing

Contributions focused on:

- Reliability improvements
- Security reviews
- Documentation
- Cross-platform consistency
- BLE mesh, DTN, and local-first synchronization experiments

are welcome.

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting changes. For suspected vulnerabilities, follow the [security reporting policy](SECURITY.md) instead of posting exploit details publicly. The [license and attribution review](docs/legal/LICENSE_AND_ATTRIBUTION_REVIEW.md) tracks outstanding pre-publication checks.

---

## 15. Documentation Map (Appendix)

Start with the [documentation index](docs/README.md) for an overview of all documentation areas.

- [docs/specs/Project_Overview_Technical_Spec.md](docs/specs/Project_Overview_Technical_Spec.md): High-level architecture, data model, transport, storage, and phase breakdown.
- [docs/server/notes/Phase_1_Known_Limitations.md](docs/server/notes/Phase_1_Known_Limitations.md): Known limitations and operational considerations for Phase 1 relay.
- [docs/milestones](docs/milestones/): Phase task lists and milestone tracking.
- [docs/specs/PHASE_2_TECHNICAL_SPEC.md](docs/specs/PHASE_2_TECHNICAL_SPEC.md): Phase 2 detailed spec (schemas, reducers, ingest, SQLite).
- [docs/specs/Cross-platform_Event_Schema_Reducer_Invariants.md](docs/specs/Cross-platform_Event_Schema_Reducer_Invariants.md): Canonical event schema + reducer invariants for cross-platform consistency.
- [docs/specs/Architecture_Diagram.md](docs/specs/Architecture_Diagram.md): System overview diagram and data flows.
- [docs/server/FEDERATION_PROTOCOL.md](docs/server/FEDERATION_PROTOCOL.md): Federation relay protocol (ingest/fetch, cursors, sync).
- [docs/server/INTERNET_RELAY_OPTION.md](docs/server/INTERNET_RELAY_OPTION.md): Optional internet relay design and gateway strategy.
- [docs/milestones/Phase2_Task_List_Android_iOS.md](docs/milestones/Phase2_Task_List_Android_iOS.md): Phase 2 task checklist by layer and platform.
- [docs/tech_notes/DB_Cooldown_On_Saturation.md](docs/tech_notes/DB_Cooldown_On_Saturation.md): Tech notes for adaptive cooldown on DB saturation.
- [docs/SECURITY.md](docs/SECURITY.md): Security model, threat scope, and limitations.
- [docs/development/BUILD.md](docs/development/BUILD.md): Build and install instructions for Android and iOS.
- [docs/DISCLAIMER.md](docs/DISCLAIMER.md): Legal disclaimer.
- [docs/code_of_conduct.md](docs/code_of_conduct.md): Community code of conduct.

---

*PeerMesh is an experimental, general-purpose research project exploring resilient communication. Source code is available; no official application binaries are currently distributed.*