# Security Policy — Research Project

PeerMesh is experimental, source-only research software. **It has known security limitations and has not undergone a comprehensive independent security audit.** It must not be relied on for sensitive, adversarial, or life-critical communication.

Read the [security architecture overview](docs/SECURITY.md), [known security limitations](docs/security/KNOWN_SECURITY_LIMITATIONS.md), and [research limitations](docs/research/KNOWN_LIMITATIONS.md) before evaluating its protections.

## Supported versions

There is currently **no supported production release, official binary, or security-maintenance version**. Source code may change, and the project does not promise security updates, response times, or coordinated release dates.

## Reporting a suspected vulnerability

**Please do not publish new exploit details, working attack code, real credentials, or sensitive user data in a public GitHub issue or pull request.**

When the GitHub repository is available, first check whether its **Security → Report a vulnerability** private-reporting feature is enabled. If it is enabled, use that channel. If it is not available, **do not assume a public issue is confidential**; seek a verified private contact channel from the repository owner before sending sensitive details. No private reporting mailbox or response SLA has been confirmed in this baseline.

Include, where safe: affected commit/variant/component, prerequisites and attacker capabilities, steps to reproduce with synthetic data, observed impact, and whether the issue overlaps a known limitation. Minimize personal data and redact keys/tokens. Do not test against other people's devices, groups, or infrastructure without permission.

## Known versus newly reported limitations

The current research baseline explicitly documents shared batch authentication material, owner authorization weaknesses, group-key revocation limitations, registry request replay, and relay plaintext admission. See [known security limitations](docs/security/KNOWN_SECURITY_LIMITATIONS.md). These disclosures do not mean other vulnerabilities are absent.

A newly discovered exploit path, unexpected privilege escalation, sensitive data exposure, or weakness beyond the documented scope is still worth reporting privately.

## Handling and disclosure

Reports may be triaged, reproduced, documented, or left unresolved depending on maintainer capacity. There is **no guaranteed acknowledgement, remediation, embargo, or disclosure timeline**. Please coordinate public disclosure when possible, while recognizing that no private reporting channel or security response service is guaranteed.

For non-sensitive bugs and documentation corrections, use the ordinary contribution process in [CONTRIBUTING.md](CONTRIBUTING.md).
