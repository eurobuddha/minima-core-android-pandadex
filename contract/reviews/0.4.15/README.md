# PandaDEX 0.4.15 — live expanded activity log

The open Activity log now receives the same timestamped events as the on-page log. It retains the existing newest-first, bounded 60-entry history and selectable text. Unchanged text is not reset on periodic repaint. Closing or destroying the Activity dismisses/releases the dialog; reopening reads current history. No transaction eligibility, confirmation, submission, keyboard or market-data change is included.

Reuse: app/src/main/java/com/eurobuddha/pandadex/MainActivity.java setStage/renderActivityLog/showActivityLog and lifecycle, with the existing Android Activity regression harness. Also inspected ../casino/app/src/main/java/com/eurobuddha/casino/MainActivity.java log/showLogDialog; its modal is a snapshot, so existing PandaDEX event rendering is extended to its dialog TextView. Sibling Salon/UTXO/PandaPools dialog lifecycle candidates were searched. Existing graph queried; pre-#1504 graph is stale and source is authoritative.

Validation: scoped code review approved; final lint 0 errors, 76 warnings. Isolated Android audit 50 passed 180 Activity assertions, including successive live updates in the same open modal, newest event first, dismiss cleanup, reopen with later events, recreation persistence and the existing portrait/landscape keyboard checks. No network permission or MinimaCore in audit package. Production source hashes are archived. Broader JVM/database suites were not rerun for this UI-only change.

Audit 48 was compiled but not run after detecting a test assumption about synchronous Android dismissal. Audit 49 passed live-modal checks then failed an older recreation assertion expecting the former latest message; audit 50 correctly expects the newly emitted confirmed fixture event. Production sources were unchanged between these audit builds. Fresh-emulator runner now checks package existence before clearing only the audit package. All versioned APKs preserved.

No push/publication. Stock-device installation waits until the user's current trade test can safely finish; replacing the running APK may interrupt that test. Earlier historical-price completeness and release blockers remain unchanged.
