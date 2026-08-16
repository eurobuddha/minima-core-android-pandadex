# pandadex — working rules

## Versioning guardrail — every code change ships with a version bump

Real funds, real chain. **Never change code without bumping the version**
(`versionCode` + `versionName` in `app/build.gradle`), so every committed state is distinct, reversible and trackable. One
logical change = one version = one commit = one push, in order. Enforced by a
pre-commit hook (, install once: Installed pre-commit guardrail: code changes now require a version bump.)
that blocks a code change with no version bump. Do **not** bypass with
. Docs/config-only commits need no bump.
