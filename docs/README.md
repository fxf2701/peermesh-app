# PeerMesh Documentation

Use this index to find product information, development guidance, technical specifications, and validation material.

## Start Here

- [Project overview](specs/Project_Overview_Technical_Spec.md): scope, architecture, and implementation phases.
- [Architecture diagram](specs/Architecture_Diagram.md) and [network model](specs/NETWORK_MODEL.md): how local mesh and optional relay services fit together.
- [Build and install](development/BUILD.md): Android and iOS build instructions.
- [Engineering principles](development/Engineering_Constitution.md) and [project bootstrap guide](development/project_bootstrap_guide_vi.md): development conventions and workflow.
- [Security overview](SECURITY.md), [disclaimer](DISCLAIMER.md), and [code of conduct](code_of_conduct.md): shared project guidance.

## Documentation by Area

| Directory | Contents |
| --- | --- |
| [specs](specs/) | System architecture, event contracts, feature blueprints, transport requirements, and phase specifications. |
| [development](development/) | Build instructions, engineering principles, and project setup guidance. |
| [mobile](mobile/) | Mobile development guides, BLE flows, nearby-room user flow, key storage, and crash logging. |
| [design](design/) | UI styles, wireframes, and feature disclosure and consent UX. |
| [server](server/) | Relay and registry protocols, deployment models, operator guides, and admin UI specifications. |
| [server/notes](server/notes/) | Server development notes, design considerations, and phase-specific limitations. |
| [moderation](moderation/) | Moderation product rules and implementation specifications. |
| [tests](tests/) | Test scenarios, verification matrices, and manual checklists. |
| [milestones](milestones/) | Phase task lists, implementation milestones, and progress records. |
| [backlogs](backlogs/) | Planned improvements and work that remains to be implemented. |
| [reviews](reviews/) | Technical reviews, audits, and runtime flow investigations. |
| [diagnostics](diagnostics/) | Diagnostic queries, state analysis, and investigation evidence. |
| [problems](problems/) | Known behavior gaps and issue analyses. |
| [tech_notes](tech_notes/) | Implementation notes and ongoing technical observations. |
| [debug](debug/) | Debugging tools and reporting workflows. |
| [map_refactor](map_refactor/) | Map refactor checklists, manual tests, and handoff records. |
| [group_invitations](group_invitations/) | Group invitation review material; see also the [Android invitation documentation](../android/docs/group_invitations/README.md). |
| [assets](assets/) | Branding guidance and visual assets. |
| [public](public/) | About-screen content, user guides, and policies intended for publication. |
| [private](private/) | Internal product planning, risk analyses, release preparation, and development-environment notes. |
| [private/beta](private/beta/README.md) | Beta program specifications, feedback, and release checklists. |

## Common Entry Points

- Nearby Rooms: [blueprint](specs/NEARBY_ROOM_BLUEPRINT.md) and [user flow](mobile/nearby_room_user_flow.vi.md).
- BLE transport: [English overview](mobile/BLE_MESH_OVERVIEW.md), [Vietnamese overview](mobile/BLE_MESH_OVERVIEW.vi.md), and [DTN implementation blueprint](specs/DTN_IMPLEMENTATION_BLUEPRINT.md).
- Relay operation: [node operator guide](server/NODE_OPERATOR_GUIDE.md) and [deployment models](server/RELAY_DEPLOYMENT_MODELS.md).
- Moderation: [product model](moderation/peermesh-play-moderation-model.md) and [Android implementation](moderation/peermesh-android-moderation-implementation-spec.md).
- UI work: [implementation design reference](design/peermesh-ui-design-spec.md) and [branding guide](assets/peer_mesh_design_system_branding_guide.md).

Phase task lists, historical notes, and proposed designs describe their stated scope and status; check the relevant current specification and implementation when using them.
