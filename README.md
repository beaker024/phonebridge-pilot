# PhoneBridge Pilot

A restricted, phone-local Android controller plus a phone-local MCP/OAuth bridge.
The pilot controls **only its separate harmless sandbox application**. It does
not call OpenAI models, host a cloud browser, use ADB, require a PC, or use a paid
service. This is source for a prototype, **not a verified installable release**.

## Status

Before this implementation, `/workspace/android-controller` contained only empty
`research/sources` directories. No earlier source, APK, build, or tests existed.

Implemented:

- Android Accessibility controller with a physical enable control, STOP/Pause bar,
  15-minute session expiry, local approval of OAuth connections, local audit,
  editable blacklist and immutable sandbox-only allowlist.
- Semantic screen tree, app-window screenshots, semantic/coordinate tap,
  focused practice-field typing, bounded swipe, sandbox launch.
- One-use observations; live tree/window/focus checks before actions; invalidation
  on events; capture checked again before export. No queued macros or retries.
- Official Python MCP SDK bridge, OAuth/PKCE, memory-only grants, native session
  checks on every authenticated request, exact callback allowlist.
- Automated bridge/security tests, real native policy JVM tests, Android source
  syntax/manifest checks, and free public-repository Android build workflow.

Not established yet:

- Full Android SDK compilation/lint or any APK artifact: required build dependencies
  are absent, and the workspace's configured download proxy is broken.
- Real Moto G Power/Android 16 behavior, background-process survival, touchscreen
  races, screenshot delivery to ChatGPT, or your account's custom plugin access.
- Termux installation of the Python SDK dependencies and live Cloudflare tunnel.
- General browser/third-party app access, Back/Home, or consequential-action
  approval. Those operations are deliberately blocked by this pilot.

The prototype is original narrow guard code, not an imported fork. The source
inspection identified `danielealbano/android-remote-control-mcp` as the strongest
reuse candidate for broader device operations. Its full repository could not be
downloaded into this workspace. See [reuse assessment](research/EXISTING_PROJECTS.md)
for exactly what was and was not inspected, and the rationale for not blindly
shipping its broader permissions/tools.

## Files

```text
android/                   Android build project (SDK 36, min Android 14)
  controller/              Native security boundary and local IPC
  sandbox/                 Harmless counter, text field and scrollable rows
bridge/                    Phone-local MCP/OAuth adapter (official MCP SDK)
tests/                     Python bridge + actual Java guard tests
scripts/                   Offline checks, phone setup/publishing, packaging
.github/workflows/         Free public-repo build and APK signature verification
docs/                      Architecture, threat model, phone setup, test plan
research/                  Evidence and reuse assessment
artifacts/                 Test evidence and source ZIP; no APK yet
```

Read [PHONE_ONLY_SETUP.md](docs/PHONE_ONLY_SETUP.md) for the exact next step.
Read [SECURITY.md](docs/SECURITY.md) before widening the allowlist.

## Local verification

```sh
bash scripts/check-local.sh
```

The cloud workspace already has the required Python packages and JVM compiler
module. The test fixture simulates the phone; it does not certify Android behavior.
The workflow additionally compiles both APKs, runs Android lint and verifies their
APK signatures. That workflow has been prepared, not executed.

## Cost boundary

Normal operation is one phone: ChatGPT + native controller + Termux bridge +
Cloudflare Quick Tunnel. No Platform API key or paid domain is used. A Quick
Tunnel provides a temporary public HTTPS communication endpoint; OAuth protects
the MCP tools. A new tunnel URL requires reconfiguring/reconnecting the plugin.

For building, a **public source repository** with a standard GitHub Actions runner
is the optional free path. Source can be public while device access remains private.
Never commit pairing keys or phone data. The workflow refuses private repositories
to avoid accidentally designing around paid build minutes. Do not enable billing,
add a card, buy larger runners, or use a trial. Artifact retention is one day.

If public source publication is unacceptable, use a restored Codex environment
for builds, or provide a dependency cache/archive. Neither option requires a PC.
See the concrete missing-resource list in [BUILD.md](docs/BUILD.md).
