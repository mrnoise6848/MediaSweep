# ADR 005 — Local-first: no accounts, no backend, no analytics

**Status:** Accepted

## Context

The app reads a user's entire photo library. Uploading any part of it — media, filenames,
hashes, counts — would be a privacy liability and would require accounts, servers and a
compliance story that the MVP does not need.

## Decision

* All scanning, hashing and classification happen on the device.
* No network permission is used by the scanner; there is no API client, no account and no
  analytics SDK in MVP.
* The local database stores media metadata and hashes that never leave the device.
* Verbose logging (paths, URIs, filenames) is restricted to debug builds; release builds do
  not log media lists.
* `docs/privacy.md` and the README privacy section describe exactly what is and is not
  collected, and only claim what the implementation guarantees.

## Alternatives

* Server-side scanning — better hardware, unacceptable privacy and connectivity cost.
* Cloud backup/restore of results — out of scope, adds account handling and data residency
  questions.

## Trade-offs

* Large libraries take longer to scan than they would on a server.
* No cross-device history of what was reviewed.

## Consequences

* "Works offline" is a structural property, not a feature flag.
* The app has no secrets, no endpoints and no third-party SDKs to audit.
* Privacy claims in the README can be verified by reading the dependency list.
