# Passphrase Storage Policy (Android, Phase 2)

Goal: never store plaintext passphrase. Prefer secure storage of derived keys; allow re-entry when needed.

## Policy
- Do not persist plaintext passphrase.
- Derive `group_key` via KDF using passphrase + salt.
- Store only:
  - `gid`, `kid`, `created_at`, `updated_at` (DB table `keys`)
  - `salt` per group (for KDF)
- Use Android Keystore + EncryptedSharedPreferences to store:
  - `group_key` (or a key-encryption key wrapping it)
  - `salt` if needed for re-derivation

## UX options
- Default: keep keys in secure storage; allow seamless access.
- If secure storage unavailable or user opts out: prompt for passphrase on app start or group switch.

## Security notes
- Always allow user to purge stored keys.
- Avoid logging passphrase or derived key material.
- Re-entry flow should be rate-limited (basic throttling).
