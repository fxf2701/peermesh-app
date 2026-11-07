# M2D3 production diff — RoomMapEventProcessor

Baseline: `d0f187ce` (`refactor-map-activity-m2d2`).

Only this live legacy ADD category-derivation hunk changed production code:

```diff
@@ -372,7 +372,15 @@ internal class RoomMapEventProcessor(
         val data = core.data as JsonObject
         val explicit = long(data, MapDomainPayloadContract.EXTENSION_VERSION_FIELD) == 1L
         val kind = string(data, "kind")
-        val category = if (explicit) MapItemCategory.fromWireValue(string(data, MapDomainPayloadContract.CATEGORY_FIELD)) else MapItemKindCatalog.categoryFor(kind)
+        val legacyKindSupported = when (identity.first) {
+            MapItemType.MARKER -> MapItemKindCatalog.supportsMarker(kind)
+            MapItemType.AREA -> MapItemKindCatalog.supportsArea(kind)
+        }
+        val category = if (explicit) {
+            MapItemCategory.fromWireValue(string(data, MapDomainPayloadContract.CATEGORY_FIELD))
+        } else {
+            MapItemKindCatalog.categoryFor(kind).takeIf { legacyKindSupported }
+        }
```

The fix makes live legacy ADD match the existing schema-51 migration's type-aware catalog
derivation. It neither changes explicit domain-aware values nor mutates existing domain state on
canonical UPD.
