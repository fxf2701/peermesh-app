# Admin UI Development Guideline

This guide defines how to build admin pages in the relay ecosystem. The Banlist
page is the reference layout and interaction pattern.

Scope:

This document applies to Relay Admin UI only.

Registry Admin UI has its own development guideline and page specifications.

See:

- [registry_admin_development_guideline.md](registry_admin_development_guideline.md)
- [registry_admin_pages_spec.md](registry_admin_pages_spec.md)

## 1) Folder/File Structure

Base path:

- `server/relay/src/public/admin/`
  - `login.html`
  - `dashboard.html`
  - `banlist.html`
  - `assets/admin.css`
  - `assets/admin.js`
- `server/relay/src/routes_admin.js` (routes + static file serving)

## 2) API Conventions

Auth:
- `POST /admin/api/auth/login`
- `POST /admin/api/auth/logout`
- `GET  /admin/api/auth/me`

CRUD pattern:
- `GET /admin/api/<resource>`
- `POST /admin/api/<resource>`
- `PATCH /admin/api/<resource>/:id`
- `DELETE /admin/api/<resource>/:id`

Input/Output:
- Requests use JSON bodies and `Content-Type: application/json`.
- Responses:
  - List: `{ "items": [...] }`
  - Create/Update: `{ "item": { ... } }`
  - Delete: `{ "ok": true }`
  - Error: `{ "error": { "code": "...", "message": "..." } }`

Auth requirements:
- All admin API calls require a valid session cookie.
- Unauthenticated requests return `401`.

## 3) UI Conventions (HTML/JS/CSS)

Layout:
- Use shared header + footer (see `login.html`, `dashboard.html`).
- Use `.shell` for consistent page width and padding.
- Use `.card` for page sections.
- Use `.banner` for read-only or safety notices.

Navigation:
- Use hamburger menu dropdown (`#menu-toggle`, `#menu`).
- Use `.auth-guest` and `.auth-user` to show/hide menu items based on session.

Breadcrumbs:
- Use the shared breadcrumbs renderer with `data-breadcrumbs` JSON.
- Example: `<section class="breadcrumbs" data-breadcrumbs='[{"label":"Back to dashboard","href":"/admin/dashboard"}]'></section>`

Forms:
- Use shared input/select styles in `admin.css`.
- Use `.grid-form` for multi-column forms.
- Use `.inline-row` for compact checkbox rows (Settings).

Tables:
- Use `.table` for data lists and summary grids.
- Use status badges: `.pill ok/offline` and `.badge` for severity.

JS patterns:
- Use `admin.js` for:
  - session check (`/admin/api/auth/me`)
  - menu toggle
  - per-page UI handlers (banlist filter, CRUD)
- Prefer centralized helpers: `setStatus`, `showToast`, `formatTimestamp`.
- For background refresh, use polling helpers with visibility pause.

Static routing:
- `/admin` -> dashboard
- `/admin/login` -> login page
- `/admin/<page>` -> specific page
- `/admin/runbooks/<name>.pdf` -> PDF download (generated server-side)

## 4) Admin UI Pages Checklist

- [x] Login
- [x] Dashboard
- [x] Banlist (interactive)
- [x] Reputation (interactive)
- [x] Federation Peers + Peer Detail
- [x] Metrics detail
- [x] Backup/Retention/Alerts cards
- [x] Settings (read-only config summary)

Phase 1.4 is reserved for Admin UI expansion. Phase 1.3 only documents the
guidelines and basic pages.
