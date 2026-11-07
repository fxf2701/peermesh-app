# Banlist signed import test note

Goal: verify CSV banlist import requires HMAC signature and rejects invalid signatures.

Prereqs:
- Admin session available.
- `BANLIST_IMPORT_HMAC_SECRET` set to a test value.

Example CSV (banlist.csv):
```csv
type,value,reason,enabled
peer_id,peer-1,abuse,true
cidr,10.0.0.0/8,botnet,true
```

Generate signature (HMAC-SHA256 over raw CSV):
```bash
# Replace SECRET with the value of BANLIST_IMPORT_HMAC_SECRET
SECRET="test-secret"
SIGNATURE=$(printf '%s' "$(cat banlist.csv)" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')

# Optional: echo the signature
printf 'signature=%s\n' "$SIGNATURE"
```

Import with curl:
```bash
curl -X POST http://localhost:8080/admin/api/banlist/import \
  -H "Content-Type: text/csv" \
  -H "X-Banlist-Signature: $SIGNATURE" \
  --data-binary @banlist.csv
```

Expected:
- `200 { "ok": true, "created": 2 }` when signature matches.
- `401 invalid_signature` when signature is missing or incorrect.
- `503 banlist_signature_unavailable` when `BANLIST_IMPORT_HMAC_SECRET` is empty.

UI flow (Admin UI):
1) Open Banlist page.
2) Choose CSV file and click Import.
3) Paste the hex signature when prompted.
4) Confirm import succeeds.

