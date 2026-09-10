# Audit36 — compiled intermediate, not executed

APK36 was built from the initial identity-session changes. Follow-up review found that new snapshots also needed to remain bound to the previously verified connection until a fresh identity read, and that cancellation batches needed to retain the original UI connection token. This APK was therefore not installed or executed and is not passing validation evidence. Its source hashes and APK are preserved for audit history.

The unused disposable emulator, isolated ADB5049 and temporary keys/data were cleaned up. Audit37 verifies the completed connection binding and follow-up fixes in the adjacent final directory. No production release or user-device/node access.
