# PeerMesh UI Design Spec

Status:
- Draft
- Scope: Android UI design tokens, shared styles, and naming conventions

Related documents:
- `docs/assets/peer_mesh_design_system_branding_guide.md`
- `android/app/src/main/res/values/colors.xml`
- `android/app/src/main/res/values/themes.xml`
- `android/app/src/main/res/values/styles.xml`

## 1. Purpose

This document is the implementation-facing UI design reference for PeerMesh Android.

Its purpose is to provide one place to look up:

- shared colors
- theme tokens
- reusable styles
- naming conventions
- when to create a new style versus reuse an existing one

This is not a Figma replacement.

It is the code-adjacent style contract used during implementation and review.

## 2. Document Roles

This document defines the implementation-facing UI reference for Android.

Use this document for:

- shared styles already defined in code
- color and theme tokens currently implemented
- naming conventions for styles and attrs
- rules for adding new reusable UI styles

Use `docs/assets/peer_mesh_design_system_branding_guide.md` for:

- brand philosophy
- visual direction
- high-level color strategy
- typography and spacing guidance at design-system level

The two documents should stay linked but separate:

- branding guide = design intent
- UI design spec = implementation reference

## 3. Design Principles

PeerMesh UI should feel:

- reliable
- clear
- low-noise
- operational rather than decorative
- readable in both dark and light themes

UI decisions should optimize for:

- clarity first
- consistency second
- compactness third

Avoid:

- ad hoc inline styling repeated across screens
- multiple visually similar button variants without clear semantic difference
- hardcoded colors when an existing token or theme attr already exists

## 4. Source of Truth

The current source of truth is split across:

- `colors.xml` for color tokens
- `themes.xml` for semantic theme mapping
- `styles.xml` for reusable component styles

When adding or changing shared UI primitives:

1. prefer semantic theme attrs over raw colors
2. prefer shared styles over repeated inline XML attributes
3. update this document when a new shared style becomes part of the design vocabulary

## 5. Theme Model

PeerMesh supports:

- `Theme.PeerMesh`
- `Theme.PeerMesh.Light`

Both themes should preserve the same product identity.

Primary brand accent remains cyan in both themes.

### 4.1 Core Theme Attributes

Defined in `themes.xml`:

- `colorPrimary`
- `colorPrimaryVariant`
- `colorOnPrimary`
- `colorSecondary`
- `colorOnSecondary`
- `android:colorBackground`
- `colorSurface`
- `colorOnSurface`
- `colorOnBackground`
- `android:textColorPrimary`
- `android:textColorSecondary`

### 4.2 PeerMesh Semantic Theme Attrs

Custom attrs currently used as semantic UI tokens:

- `peerMeshHeaderSurface`
- `peerMeshNoticeSurface`
- `peerMeshMenuDivider`
- `peerMeshSecondaryButtonSurface`
- `peerMeshChatMessageSurface`
- `peerMeshChatMessageStroke`
- `peerMeshChatReplySurface`
- `peerMeshChatReplyStroke`
- `peerMeshChatMessageHighlightSurface`

Rule:

- when a component concept already maps to one of these attrs, use the attr
- do not replace these with raw `@color/...` references in screen XML unless there is a strong reason

## 6. Color Tokens

Current implementation tokens in `colors.xml` include:

### 5.1 Dark Theme Base

- `peer_mesh_bg`
- `peer_mesh_surface`
- `peer_mesh_surface_chat`
- `peer_mesh_surface_2`
- `peer_mesh_text_primary`
- `peer_mesh_text_secondary`
- `peer_mesh_text_disabled`
- `peer_mesh_border`
- `peer_mesh_border_soft`
- `peer_mesh_menu_divider`

### 5.2 Light Theme Base

- `peer_mesh_light_bg`
- `peer_mesh_light_surface`
- `peer_mesh_light_surface_chat`
- `peer_mesh_light_surface_2`
- `peer_mesh_light_header`
- `peer_mesh_light_notice_bg`
- `peer_mesh_light_text_primary`
- `peer_mesh_light_text_secondary`
- `peer_mesh_light_text_disabled`
- `peer_mesh_light_border`
- `peer_mesh_light_border_soft`
- `peer_mesh_light_menu_divider`

### 5.3 Brand Accent

- `peer_mesh_primary`
- `peer_mesh_primary_dark`
- `peer_mesh_primary_light`
- `peer_mesh_secondary`

### 5.4 Status Colors

- `status_on`
- `status_off`
- `status_paused`
- `status_error`
- `active_dot`

Rule:

- status colors are for state signaling, not for generic accent decoration

## 7. Shared Styles

Current reusable styles are defined in `styles.xml`.

### 6.1 `PeerMeshDrawerItemText`

Usage:

- navigation drawer text appearance

Contract:

- on-surface text
- bold

Use when:

- the UI element is a drawer/menu label and should match drawer styling

### 6.2 `PeerMeshHintText`

Usage:

- helper text
- low-emphasis explanatory copy
- empty-state support text
- settings summaries and non-critical hints

Current contract:

- `android:textColor = ?android:attr/textColorSecondary`

Examples:

- `Scan a group QR code or paste an invitation link to join.`
- Settings helper copy under toggles or sections

Use when:

- the text is informative but not primary content
- the line should visually match other hint/support copy across screens

Do not use when:

- the text is an error
- the text is a warning banner
- the text is a headline or section label

### 6.3 `PeerMeshButtonBase`

Usage:

- shared base for all Material button variants

Current contract:

- min height from dimen token
- shared text size
- shared horizontal and vertical padding
- `cornerRadius = 12dp`

Rule:

- new shared button variants should inherit from `PeerMeshButtonBase`

### 6.4 `PeerMeshButtonPrimary`

Usage:

- default call-to-action button

Current contract:

- background = `?attr/colorPrimary`
- text = `?attr/colorOnPrimary`

Use when:

- the action is the primary next step on a screen or dialog

### 6.5 `PeerMeshButtonSecondary`

Usage:

- secondary CTA
- supporting action buttons
- low-risk navigation/action triggers

Current contract:

- background = `?attr/peerMeshSecondaryButtonSurface`
- text = `?attr/colorPrimary`
- stroke = `?attr/colorPrimary`
- stroke width = `1dp`

Examples:

- `Scan QR Code`
- secondary support actions
- feedback CTA in Settings

Use when:

- the action should be discoverable and tappable
- but should not visually overpower the screen’s primary action

## 8. Typography Guidance

The current XML implementation mostly relies on:

- default Material typography
- explicit bold section labels
- semantic color contrast

Until a richer typography system is introduced, use these functional categories:

### 7.1 Section Title

Use:

- `TextView`
- `android:textStyle="bold"`
- `?attr/colorOnSurface` or `?attr/colorOnBackground`

Examples:

- `Permissions`
- `Cloud Sync`
- `Help Improve PeerMesh`

### 7.2 Body Text

Use:

- normal `TextView`
- primary text color

### 7.3 Hint / Support Text

Use:

- `@style/PeerMeshHintText`

### 7.4 Action Label

Use:

- button styles rather than hand-tuning button text attributes in screen XML

## 9. Spacing Guidance

The current layouts broadly follow:

- `4dp` for tight spacing within a block
- `6dp` for micro separation under a CTA or label
- `8dp` for normal control separation
- `12dp` for compact section spacing
- `16dp` for base screen padding
- `20dp` for larger section gaps in Settings

Rule:

- if the same spacing relationship repeats across screens, prefer consistency over novelty
- if a new reusable component emerges, consider moving spacing to a shared style

## 10. Component Rules

### 9.1 Buttons

- use `PeerMeshButtonPrimary` for main CTA
- use `PeerMeshButtonSecondary` for secondary CTA
- do not mix custom inline button borders/colors if an existing button style already matches

### 9.2 Hint Text

- use `PeerMeshHintText` for helper copy
- avoid inline `textColorSecondary` repetition
- if a hint needs more specific semantics later, derive a variant style rather than copying attributes everywhere

### 9.3 Section Headers

- bold
- high-contrast
- short and scannable

### 9.4 Settings Summaries

- should use hint/support styling
- should remain short
- should explain tradeoffs, not marketing

## 11. Naming Conventions

### 10.1 Style Names

Use:

- `PeerMesh<Component><Variant>`

Examples:

- `PeerMeshHintText`
- `PeerMeshButtonPrimary`
- `PeerMeshButtonSecondary`

### 10.2 Theme Attr Names

Use semantic names tied to UI role, not raw appearance.

Good:

- `peerMeshNoticeSurface`
- `peerMeshSecondaryButtonSurface`

Avoid:

- names tied only to one screen
- names that encode a raw color rather than a role

### 10.3 Color Token Names

Use:

- `peer_mesh_<role>`
- `peer_mesh_light_<role>`

This should remain the pattern for future shared tokens.

## 12. When to Add a New Shared Style

Create a new shared style when all are true:

- the same visual pattern appears in at least two places, or clearly will
- the pattern has semantic meaning
- reusing inline XML would increase drift risk

Do not create a shared style when:

- the styling is one-off
- the component is still experimental
- the only shared property is a single margin value

## 13. Review Checklist

Before merging a UI change, check:

- are raw colors avoidable through existing attrs or styles?
- is there an existing shared style already matching this pattern?
- should repeated helper text be converted to `PeerMeshHintText`?
- does the component work in both dark and light themes?
- is the naming consistent with existing `PeerMesh...` conventions?

## 14. Immediate Follow-up Candidates

Good next candidates for standardization:

- section title text style
- notice/banner text style
- empty state text style
- card/container style for reusable settings blocks
- dialog form label style

These should be added incrementally rather than all at once.
