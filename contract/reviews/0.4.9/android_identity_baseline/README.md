# Audit35 — stale wallet identity reproduced

Built against unchanged PandaDEX 0.4.8/408 Java. Component phases passed 808 normal +16 before deliberate process death +31 recovery assertions. The full Activity phase failed its new reconnect guard: after beginning a new pairing, simulated fresh-key and covenant replies let the real ready() return true while the previous wallet identity remained and getaddress had not replied. No transaction was submitted. The fixture is controlled ordering of local Activity observations, not a real node response or stock-device test.

The failed run and APK35 are preserved. The exact owned emulator, isolated ADB5049 and temporary keys/data were cleaned up. Audit36 exercises the fix and additional stale callbacks/queue dispatch cases.
