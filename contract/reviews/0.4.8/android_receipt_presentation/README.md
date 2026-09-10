# Audit33 — retained failed run

Component phases passed 808 normal +16 before deliberate process death +31 recovery assertions. The full Activity phase completed 96 assertions, including the new receipt/control fixtures, then failed its immediate foreground-cleanup check after finish() and waitForIdleSync(). This run is not recorded as a passing full audit.

The harness did not wait for a pause/destroy lifecycle callback. Audit34 preserves the same production source and fixtures, using the existing bounded CountDownLatch pattern to await the actual Instrumentation.callActivityOnDestroy callback before asserting cleanup. It also scrolls Trade's existing ScrollView to expose receipts in its screenshot. No production lifecycle code was changed to make the test pass.

APK33 and all failure logs are retained. Its exact owned emulator, isolated ADB5049 and temporary data/keys were cleaned up. No user device or node was accessed. See the separate verified audit directory for the follow-up outcome.
