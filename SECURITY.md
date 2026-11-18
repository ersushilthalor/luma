# Security policy

## Reporting a vulnerability

Do not open a public issue for security problems. Use GitHub's private vulnerability reporting ("Report a vulnerability" on the repository's Security tab) once the repository is published. Include the affected version or commit, the device and firmware, and steps to reproduce.

## Scope

Relevant reports include:

- Leaks of location or other personal data in saved images, DNGs, diagnostics reports or logs
- Files written outside the intended locations (`DCIM/Luma/`, app-internal storage, app-specific external storage)
- Memory-safety issues in the native engine, including through crafted metadata or DNG files
- Any network access by the app (Luma is designed to have none)

## Design commitments

See [docs/SECURITY_AND_PRIVACY.md](docs/SECURITY_AND_PRIVACY.md). Summary: no `INTERNET` permission (MediaPipe's network permissions are removed from the merged manifest; its segmentation model is bundled and runs offline), no analytics or crash SDKs, location tagging off by default, no device identifiers in outputs, and app data excluded from backup.
