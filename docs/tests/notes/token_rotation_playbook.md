# Token Rotation Playbook Test Notes

Status: not tested yet.

## Why not tested

- Requires a multi-node relay setup and real peer traffic.
- Rotation impacts live auth tokens.

## Preconditions

- At least two relay nodes with federation enabled.
- Known peer tokens and request signature secrets.

## Validation Steps

- Rotate peer tokens following the playbook.
- Confirm peers continue to sync without authentication errors.
- Verify old tokens are rejected after the grace period.
