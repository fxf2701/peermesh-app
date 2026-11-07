# 🎨 PeerMesh Design System & Branding Guide

Related implementation reference:
- `docs/design/peermesh-ui-design-spec.md`

---

## 📚 Document Roles

This document defines the visual and brand direction for PeerMesh.

Use this document for:
- brand philosophy
- color direction
- typography direction
- spacing principles
- high-level component intent

Use `docs/design/peermesh-ui-design-spec.md` for:
- Android implementation tokens
- shared XML styles
- theme attrs
- naming conventions
- when to reuse or add styles in code

---

## 🎯 0. Brand Philosophy

**PeerMesh = Works when others fail**

Design phải thể hiện:
- Reliability (tin cậy)
- Simplicity (đơn giản)
- Connectivity (kết nối)
- Resilience (bền vững)

Tránh:
- Màu mè
- Quá social-app
- Quá hacker / cyberpunk

---

## 🌈 1. Brand Color (Primary)

```css
Primary:        #22D3EE   // Cyan (main brand)
Primary Dark:   #06B6D4
Primary Light:  #67E8F9
```

### Ý nghĩa
- Cyan = network / signal / connectivity
- Khác biệt với chat app phổ thông
- Phù hợp positioning kỹ thuật

---

## 🌑 2. Dark Theme (Default)

```css
Background:     #0F172A
Surface:        #111827
Surface 2:      #1F2937

Border:         #374151
Divider:        #1F2937
```

### Nguyên tắc
- Dark là theme chính
- Tạo cảm giác “network tool”
- Giảm chói mắt

---

## 🌕 3. Light Theme

```css
Background:     #FFFFFF
Surface:        #F9FAFB
Surface 2:      #F3F4F6

Border:         #E5E7EB
Divider:        #E5E7EB
```

### Nguyên tắc
- Dùng cùng Primary color với Dark
- Không thay đổi brand identity

---

## 📝 4. Text Colors

### Dark Theme

```css
Text Primary:   #F9FAFB
Text Secondary: #9CA3AF
Text Disabled:  #6B7280
```

### Light Theme

```css
Text Primary:   #111827
Text Secondary: #6B7280
Text Disabled:  #9CA3AF
```

---

## ⚠️ 5. Status Colors

```css
Success: #22C55E
Warning: #F59E0B
Error:   #EF4444
```

### Rule
- Không dùng làm accent chính
- Chỉ dùng cho trạng thái

---

## ✍️ 6. Typography

### Font
- Roboto (default Android)
- hoặc Inter (modern hơn)

### Scale

```css
Title:        20–22sp
Section:      16–18sp
Body:         14–15sp
Caption:      12–13sp
```

### Weight

```css
Title:    600
Section:  500
Body:     400
Caption:  400
```

### Rule
- Không quá 4 size
- Ưu tiên readability

---

## 📐 7. Spacing System (8pt Grid)

```css
4dp   = micro
8dp   = base
12dp  = compact
16dp  = standard
24dp  = section gap
32dp  = large section
```

### Usage
- Item spacing: 12dp
- Section spacing: 24–32dp

---

## 🧩 8. Component Guidelines

### Checkbox / Radio

```text
Selected: Primary (#22D3EE)
Unselected: Gray (#6B7280)
```

❌ Không dùng màu vàng

---

### Button

#### Primary

```css
Background: Primary
Text: White
Radius: 12dp
Padding: 12–16dp
```

#### Secondary

```css
Border: 1px solid Primary
Text: Primary
Background: transparent
```

---

### Card / Section

```css
Background: Surface
Radius: 12–16dp
Padding: 16dp
```

---

## 🌗 9. Theme Consistency

### Rule quan trọng

```text
1 app = 1 primary color
```

Không làm:
- Dark = màu A
- Light = màu B

Phải làm:
- Dark + Light cùng dùng Cyan

---

## 🔘 10. Icon System

### Style
- Minimal
- Rounded
- Stroke 1.5–2dp

### Color

```text
Default: Text Secondary
Active: Primary
```

---

## ✨ 11. Interaction

### Checkbox / Radio
- Animation 150ms
- Scale nhẹ

### Button
- Opacity: 0.9 khi press

---

## 🎯 12. Brand Tone

PeerMesh nên cảm thấy:

```text
Reliable
Calm
Technical
Minimal
```

Không phải:

```text
Flashy
Social media
Gaming
```

---

## 🔥 13. Quick Implementation Checklist

- [ ] Replace màu vàng → cyan
- [ ] Unify dark/light accent color
- [ ] Apply spacing 24–32dp giữa section
- [ ] Limit typography scale
- [ ] Update icon theo mesh concept

---

## 🚀 14. Future Expansion

- Design Token system (JSON)
- Material 3 integration
- Animation system
- Brand guideline full

---

**End of Document**
