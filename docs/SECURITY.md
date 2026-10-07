# Threat model and security limits

Assets: the user's device state, account sessions, credentials, local pairing key,
OAuth grants, control availability, and audit history. Protected boundaries are
native Android policy, app-private storage, loopback bearer authentication, public
MCP OAuth and the physical user's approval/STOP controls.

| Threat | Implemented mitigation | Limit / verification still needed |
|---|---|---|
| Internet caller or guessed URL | OAuth on MCP; PKCE; one-time codes; exact callbacks; memory-only scoped tokens | Live ChatGPT client interop not tested |
| Stolen OAuth token | Short expiry; native session validation every request; local STOP/re-enable revokes it | A valid stolen token can act in sandbox until revoked |
| Other Android app reaches loopback | Separate 256-bit pairing bearer; fixed address; no Origin; strict bounded HTTP; app-private key | Root/privileged malware or leaked pairing key is outside the trusted-device assumption |
| Prompt injection from screen | Tiny native tool allowlist; immutable sandbox target; no settings/file/admin tools | Model instructions alone are not a security boundary |
| Controller self-modification | Controller package denied; no settings, permission, policy, enable or shell endpoints | User retains physical control of their own configuration |
| Password manager or system dialogs | All non-sandbox apps denied; foreign/focused system windows rejected | Must be verified on the real Moto; no arbitrary apps are currently supported |
| Password/auth text in observation | Check password/input/sensitivity flags before text; deny protected/auth screens; metadata-first capture gate | Unmarked secrets cannot be identified perfectly; never put real secrets in sandbox |
| Screenshot leak | Only approved sandbox-window capture; no full-display fallback; session/tree recheck before exporting | Pixel/tree race cannot be perfectly synchronized across arbitrary apps; arbitrary apps remain blocked |
| Human changes UI while agent plans | Event invalidation, one-use snapshot, fresh digest/window/focus checks, settle period, Pause | Input attribution is heuristic; already-dispatched tap cannot be undone |
| Remote operation remains enabled silently | Mandatory notification permission + persistent STOP/Pause overlay; explicit local enable; expiry; restart defaults off | Moto overlay/service/background behavior still needs hardware testing |
| Consequential action | No application with such actions can be targeted; no install/message/delete/purchase/system intent tools | General-app consequence classification/confirmation is NOT implemented |
| Data in logs/backups | Native audit omits arguments/text/screens/tokens; native backup disabled; bridge access logs off | Android/ChatGPT/Cloudflare may have their own service metadata logs |
| Flooding or oversized requests | 16 KiB incoming public/IPC limits; bounded registrations/grants/tree/image; serial native actions | Flooding can deny availability; Quick Tunnel has no SLA |
| Sidecar/relay compromise | Controller validates session/policy independently; relay never receives native pairing key via MCP | Relay terminates TLS and can see sanitized MCP payloads; not end-to-end opaque |

## Requirements matrix

- Explicit local activation, expiry, visible indication and STOP: implemented in
  Android source; actual overlay/notification availability must be tested.
- Authenticated connections: OAuth/PKCE on remote MCP, distinct local IPC key;
  actual native socket parser needs Android integration testing.
- Disabled rejection: native guard precedes control and observation; bridge tokens
  require the same live session. `/status` returns only session/flags to the local
  authenticated bridge, even when disabled; it does not perform device commands.
- Local audit: command/outcome/timestamp only; failed requested-audit write blocks
  execution. Capture/gesture callbacks use fixed rejection messages.
- Password/auth/payment protection: protected flag screening and whole-app denial,
  supplemented by conservative labels. The current pilot never targets login or
  payment-capable apps. It does not extract stored passwords, cookies or credentials.
- Blacklist: physically editable; saving stops control. Hard allowlist takes
  precedence and cannot be remotely widened.
- Controller permission/safeguard protection: no remote setter; controller's own
  UI is secured and outside the allowed target. All Android permission grants are manual.
- CAPTCHA: no solving mechanism; pause for manual work. Arbitrary websites are
  blocked, so real CAPTCHA detection/continuation remains a future adapter feature.
- Consequential confirmation: currently enforced by **making consequential targets
  unreachable**. No claim that this pilot can safely classify all arbitrary buttons.

## Android 16 restrictions

The service declares `isAccessibilityTool=false`. This is not an accessibility
product for people with disabilities, and it must not claim special exemptions.
Android [sensitive Accessibility data protection](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)
can deny views to this service. Protected screenshot failures, unavailable hierarchy
and secure windows are material limits, not permissions to bypass.

MediaProjection is not used: the [official API](https://developer.android.com/media/grow/media-projection)
requires fresh consent/session handling and its own foreground-service setup.
UIAutomator/ADB is not a normal unprivileged background app API and would add
debugging privileges. No root, same-device wireless ADB, Shizuku or Play registration
is needed by this design. Google Play/Play Protect may still flag or block sideloaded
automation services. Do not disable security protection to make this prototype run.

## Future browser boundary

Before allowing Chrome/Amazon, add app-specific restricted screen policies,
password-manager/IME-overlay handling, sensitive-input handoff and a final-action
pause mechanism. A generic semantic label such as "Continue" can submit an order,
send a message or approve a permission. Regex labels cannot provide a guarantee.
Unknown state must fail closed. Do not extend the allowlist as a shortcut to testing
on real accounts. Existing logged-in sessions may eventually be used without
extracting their cookies/passwords, but nothing in this pilot accesses them.
