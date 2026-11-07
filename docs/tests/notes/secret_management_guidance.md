# Secret Management Guidance Test Notes

Status: not tested yet.

## Why not tested

- This item is documentation-oriented and depends on operator environment.
- Verification requires a real secret manager or vault integration.

## Preconditions

- Choose a secret manager (Vault, AWS Secrets Manager, GCP Secret Manager, etc.).
- Ensure relay config reads secrets from that manager.

## Validation Steps

- Confirm no secrets are stored in git or plain files.
- Rotate secrets and ensure the relay reloads them.
- Verify secrets are not printed in logs or metrics.
