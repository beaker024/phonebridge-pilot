# Harmless first proof of concept

## Automated tests already runnable offline

`bash scripts/check-local.sh` runs the real Python MCP SDK/OAuth HTTP stack through
in-memory ASGI, against an authenticated native-transport fixture. It tests
authorization without remote approval, PKCE mismatch, code expiry/replay, native
revocation, session changes, unknown tools, schema validation, no retries,
body/Origin/Host limits, initialization and tap/type/swipe observation round trips.

The JVM runs the **same GuardPolicy class used by the Android service**, covering
app/controller/settings/password-manager denial, blacklist, snapshot mismatch,
window change, expiry, recent events, text/control limits and coordinate exclusion.
Syntax and manifest checks cover all Android sources. These do not simulate the
Android framework, approve physical permissions, produce an APK or prove hardware
behavior. Evidence is in `artifacts/`.

## Hardware preconditions

- Both APKs compiled successfully and installed manually on Moto G Power/Android 16.
- Pilot Accessibility Service enabled, notification permission granted and STOP
  bar visible. Only Sandbox is installed as a control target; no real accounts.
- Termux MCP bridge and Quick Tunnel running on the same phone. OAuth connection
  physically approved and plugin available in the user's Android conversation.
- Do not enter actual passwords, codes, payment data or other secrets in Sandbox.

## Local safety checks BEFORE connecting ChatGPT

1. Start Sandbox and physically increment its counter/type `manual test`/scroll.
   Touchscreen must remain normal when Pilot is enabled. No special touch gestures
   or exclusive input mode should be needed.
2. Tap red STOP. The bar should disappear, status should be disabled, remote
   observations/actions should fail and prior OAuth grants should be unusable.
3. Re-enable manually. Confirm that old grants still fail and a new OAuth approval
   is required. Pause/Resume should invalidate a previously issued observation.
4. Switch to Settings, Pilot or another app. `get_screen`/tap/typing/swipe must be
   refused. Do not attempt to approve a permission remotely.

## ChatGPT prompt

```text
Use only my PhoneBridge Pilot plugin. Launch its harmless sandbox, then obtain
the current screen including Accessibility metadata. Locate "Increment harmless
counter" semantically and tap its element ID using the newest snapshot. Obtain
a fresh screen and verify the count. Focus the practice text field, obtain a new
screen, and enter "hello phone" into that focused field. Obtain a fresh screen
and verify the text. Swipe upward within the scrollable harmless rows, then
obtain the updated screen with a screenshot if available. Stop if the controller
reports a pause, blocked app, stale state, or protected screen. Never use another
browser or tool and never request secrets.
```

Read-only observations can be requested again after `screen-settling` without
repeating a mutation. Every input uses a new observation and only one action.
Snapshot IDs expire after 90 seconds; refresh if reasoning took longer. If capture
fails, first test metadata only; do not disable secure capture protections.

## Shared-control test

After an observation, manually tap/scroll/type in Sandbox before the pending agent
action. It should report stale state and require a new observation. Then tap Pause,
handle a harmless step manually, tap Resume and let ChatGPT observe again. Check
for stuck pause, unexpected duplicate actions or touch interference.

An already-dispatched tap may finish before STOP arrives. Gestures are capped at
300 ms. This limit must be reported honestly; the system cannot undo completed input.

## Evidence to return for debugging

- Android app version/build, Moto Android version and whether the STOP bar appears.
- Green/red build result and any Android compile/lint error text.
- Fixed tool error messages, which primitive failed, and whether the foreground
  app was Sandbox, ChatGPT, controller or keyboard at the time.
- Native audit command/outcome records, with no keys or screen secrets.
- If needed, a screenshot **of the harmless Sandbox only**, taken manually.

Never send local pairing keys, OAuth access tokens, browser cookies, GitHub login
tokens, real credentials or private account screenshots. No consequential testing
is included in this milestone.
