# PeerMesh Disclaimer

PeerMesh supports local group communication during hiking, camping, outdoor activities, travel in remote or wilderness areas, and everyday interactions in nearby spaces such as cafes. It can help nearby participants exchange information where internet access is limited, unavailable, or unreliable.

This document explains the application's practical limitations so users can decide how it fits their activities.

## Availability and Delivery

Communication is best-effort. PeerMesh does not guarantee continuous availability, immediate synchronization, or delivery of every message.

Performance depends on:

- Distance between devices, obstacles, wireless interference, and the presence of nearby participating devices.
- Device hardware, battery level, permissions, and operating system restrictions on background activity.
- Internet connectivity and relay availability when using optional online synchronization.

Messages may be delayed or remain undelivered. Do not rely on PeerMesh as the only way to communicate when timely contact is essential.

## Shared Information and Outdoor Use

Messages, map markers, locations, and trails are shared by participants. They may be incomplete, inaccurate, or outdated. Check timestamps and verify important information before acting on it.

PeerMesh does not replace emergency services, professional advice, dedicated communication equipment, or navigation tools appropriate to the activity. A shared marker or trail does not establish that a location or route is safe.

## Privacy, Security, and Stored Data

Group-content encryption is a design and implementation area, but **does not establish that all group operations are authenticated or that removal securely revokes future access**. The reviewed baseline has a static shared batch-HMAC key, insufficient independent cryptographic proof for some owner-only actions, and group rekey distribution that may remain readable to removed members holding the old key. Encryption does not hide all connection metadata or guarantee protection if a device or group key is compromised. Users should protect their devices and group secrets and consider who can receive information they share.

**Do not use this baseline for sensitive, adversarial, or life-critical communications.** See [Known Security Limitations](security/KNOWN_SECURITY_LIMITATIONS.md) for technical details and the distinction between verified code observations and audit-reported risks.

Data may expire, be removed during cleanup, or become unavailable because of device failure. Expiration and deletion do not guarantee secure erasure from device storage **or deletion of copies already received by other devices**.

See the [Security Overview](SECURITY.md) for details about encryption, metadata, key management, and device limitations.

## Third-party Components and Redistribution

PeerMesh depends on device hardware, operating systems, libraries, and optional services. Their availability, behavior, and security may affect the application.

Anyone modifying or redistributing PeerMesh should describe their changes and limitations accurately, preserve relevant notices, and comply with the applicable software licenses. Downstream modifications and deployments are independent of the original project.

## Warranty and Liability

This software is provided **"AS IS"**, without warranty of any kind, express or implied. The authors, contributors, and distributors make no guarantees regarding availability, reliability, security, or suitability for a particular purpose.

To the maximum extent permitted by law, the authors and contributors shall not be liable for direct, indirect, incidental, consequential, or punitive damages arising from use or inability to use the software, data loss or corruption, or personal injury or harm.
