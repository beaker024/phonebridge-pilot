# Exact phone-only handoff

## Current dependency: obtain a build environment, not a PC

There is **no APK yet**. The cloud workspace has source and passing offline tests,
but no Android SDK/Gradle/dependency cache. The configured shell proxy is broken;
it was not retried or bypassed in this implementation turn. Installing source ZIPs
does not install an Android app.

The shortest independent path is to use a free **public-source GitHub Actions**
build from this same phone. This needs a free GitHub account, browser authentication
and your deliberate choice to publish the source. It does not publish phone data or
make device-control sessions public. Do not enter a payment method or enable billing.
[Standard public-repository runners are free](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).
The workflow refuses private repositories and uploads small build artifacts for one day.

**Your exact next physical action:** download `phonebridge-pilot-source.zip` from
the supplied workspace artifact and, if acceptable, create/sign into a free GitHub
account on your phone. Then follow the short Termux publishing sequence below.
You do not need to install Android Studio, attach USB, enable ADB or compile on the phone.

### Publish source and build APKs using the phone

1. Install [Termux from its official F-Droid listing](https://f-droid.org/packages/com.termux/)
   or [official GitHub releases](https://github.com/termux/termux-app/releases).
   Use a consistent installation source for any optional add-ons. Termux and GitHub
   are free; there is no API credit purchase.
2. In Termux, run:

   ```sh
   pkg update
   pkg install git gh unzip
   termux-setup-storage
   ```

   Allow the storage prompt yourself. Copy/download the source ZIP to Downloads.
   Extract into Termux's private home (not shared storage):

   ```sh
   mkdir -p ~/phonebridge-work
   cd ~/phonebridge-work
   unzip ~/storage/downloads/phonebridge-pilot-source.zip
   cd android-controller
   gh auth login
   ```

   Choose GitHub.com → HTTPS → sign in using browser. Complete authentication
   yourself. Never paste a GitHub token or password into this ChatGPT conversation.

3. After deciding to publish this source publicly:

   ```sh
   bash scripts/publish-from-phone.sh
   ```

   The script creates a public repository named `phonebridge-pilot` on **your own
   account**, commits source/configuration only and pushes it. It checks for private
   key files and does not publish pairing configuration. It fails rather than
   overwriting an existing repository. No repository has been created for you yet.

4. In the phone browser, open your repository → **Actions** → **Free public-repository
   pilot build**. Wait for a green result. Download `phonebridge-pilot-apks`, extract
   it with Android's Files app and find `phonebridge-pilot.apk` and
   `phonebridge-sandbox.apk`. Keep the APK hash text. If the workflow fails, send
   its error text or log file here; do not try random dependency changes.

This is an authored build configuration, not a remotely executed build. CI may
find Android API/type/lint issues not detectable without the SDK. We will repair
any concrete failures using its logs. If source publication is unacceptable,
the alternative is restoring the existing Codex environment or providing a build
cache. See [BUILD.md](BUILD.md); no PC is needed for either option.

## Install the two APKs once available

1. Tap `phonebridge-sandbox.apk` in Files. If Android asks, temporarily authorize
   this installer source and approve installation yourself. Then install
   `phonebridge-pilot.apk`. Revoke the installer-source permission afterward.
2. Open **PhoneBridge Pilot** → **Open Accessibility settings**. Enable its service
   yourself after reading Android's warning. A sideloaded app may require Android's
   per-app "Allow restricted settings" flow. If Play Protect or device policy blocks
   it, report the exact message; do not disable protections or grant unrelated permissions.
3. Return to Pilot → **Allow required notification indication** and approve the
   notification prompt yourself. No camera, location, contacts, files, notification
   listener, root, device administration or ADB access is requested.
4. Tap **Enable control for 15 minutes**. Confirm the red STOP/Pause bar and persistent
   notification. STOP must work locally before any remote test.

## Configure the bridge on the same phone

This is a candidate runtime path. Python SDK native-dependency installation on
Termux has not yet been tested on the Moto, so preserve any error for diagnosis.
No model API key is used. The local pairing key stays between Pilot and Termux.

```sh
pkg install python python-pip rust clang make cmake ninja pkg-config openssl libffi cloudflared
cd ~/phonebridge-work/android-controller
python -m pip install -r bridge/requirements.txt
python -m bridge.server --pair
```

Open Pilot's **Show local pairing key for Termux**, copy its key yourself, and paste
only into the hidden Termux prompt. It is stored in Termux's private directory with
mode 0600. Do not paste it into ChatGPT, a public repository, the tunnel command,
an environment variable printed in logs, or a web form. Clear the clipboard after
pairing; Pilot hides its key after a minute. If replacing a key, delete the old
`~/.phonebridge/pairing.key` locally before running `--pair` again.

In one Termux session:

```sh
cloudflared tunnel --url http://127.0.0.1:8765 --protocol http2 --no-autoupdate
```

Read the temporary `https://…trycloudflare.com` URL. Only the **MCP bridge port 8765**
is tunneled. **Never tunnel controller port 8766.** [Quick Tunnels need neither a
Cloudflare account nor a domain](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/).
They have no uptime guarantee and are for temporary testing. This bridge uses
Streamable HTTP **JSON responses**, not SSE (Quick Tunnels do not support SSE).
HTTP/2 here is the phone's outbound tunnel transport, not an MCP SSE requirement.

In the same phone's browser, visit ChatGPT → Plugins → Add custom MCP server.
Set a draft connection to `https://YOUR-TUNNEL.trycloudflare.com/mcp`, choose OAuth,
and note the **exact callback URI shown by the app setup**. Do not select "No
authentication". You may need to open an advanced setup/management page to find it.
If setup exposes no callback, report that UI; do not guess a callback or widen the
allowlist to arbitrary URLs. The common legacy callback below is an example only.

In a second Termux session, replace both values with your displayed values:

```sh
cd ~/phonebridge-work/android-controller
python -m bridge.server \
  --public-url https://YOUR-TUNNEL.trycloudflare.com \
  --redirect-uri https://chatgpt.com/connector_platform/oauth/redirect
```

Complete the ChatGPT connection. Its OAuth page displays a comparison code.
Switch to Pilot, verify the same code, physically approve the connection, then
return to the OAuth browser page and tap **Check approval**. Install the resulting
personal plugin. Select it with `@` in the Android conversation if available.
If it is missing there, record your app version/plan and visible Plugins/Work UI;
the account-specific mobile route remains an unresolved dependency. No subscription
upgrade or independent API fallback is authorized by this project.

Allow Termux to keep these foreground sessions running using its normal visible
notification. Android/Motorola battery restrictions may kill background processes;
failure must stop control rather than auto-enable it. Do not enable boot autostart.
Closing Termux or stopping the tunnel disconnects access; the red native STOP bar
revokes native access immediately regardless of the tunnel.

## First test

Follow [TEST_PROCEDURE.md](TEST_PROCEDURE.md). Keep both Termux sessions alive,
locally re-enable/reapprove if the 15-minute session expired, and send the provided
harmless prompt. `launch_app` can bring Sandbox forward from ChatGPT. If Android
blocks that launch, manually switch to Sandbox after sending the task. Agent tools
are asynchronous; ChatGPT and Sandbox need not both be foreground simultaneously.

Changing tunnel URL, restarting the bridge or starting a new native session
requires reconnecting/reapproving. Temporary-URL usability is a known limitation;
there is no paid stable gateway hidden in this design.
