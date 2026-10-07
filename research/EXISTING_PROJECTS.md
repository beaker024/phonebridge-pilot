# Existing-project assessment — 2026-10-06

This continues the thread's feasibility work; it is not a new theoretical Android
automation investigation. Shell downloads are unavailable through the broken
workspace proxy. A separate documentation connector retrieved some source pages.
No complete repository was cloned or imported, no upstream test suite was run,
and no maintenance/security assurance is inferred from README promises or stars.

| Project | Practical fit | Inspection and result |
|---|---|---|
| [anotb/phone-use](https://github.com/anotb/phone-use) | Closest documented ChatGPT/same-phone architecture | README and architecture/gateway documents inspected. Uses upstream native controller plus pairing gateway; reports Android Work experiments. Operator-managed gateway onboarding remains a gap. Workstation/emulator testing is not proof of the user's physical Moto. A hosted gateway is not assumed free. No full source audit completed. |
| [danielealbano/android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp) | **Best candidate for broader native reuse/fork** | Inspected actual `McpAccessibilityService.kt`, portions of `McpServerService.kt`, `AndroidManifest.xml` and `app/build.gradle.kts`. On-device server, native Accessibility/screenshot paths, OAuth components and tunnels fit phone-local operation. Current app includes broad tools/permissions and a large native/dependency build, so it must not be treated as a restricted pilot simply by trusting model instructions. |
| [bayshier/android-mcp](https://github.com/bayshier/android-mcp) | ADB host wrapper; not selected for runtime | Repository/README structure inspected. Uses a JVM stdio MCP server and ADB; image + compact hierarchy design is useful. Direct AdbClient source retrieval failed. Same-device Termux/wireless ADB might be possible, but is unproven here and adds debugging privileges rather than a native local master guard. |
| [NeoAgentman/mobile-use-mcp](https://github.com/NeoAgentman/mobile-use-mcp) | Good observation/selector reference; not selected for runtime | Actual `src/mobile_use_mcp/server.py` inspected. Uses FastMCP, image+tree observations, session snapshots and serialized writes. Runtime backend remains ADB/uiautomator2; no phone-native activation/secret guard is supplied by the server entry point. |
| [benasbarciauskas/androir-mcp](https://github.com/benasbarciauskas/androir-mcp) | ADB/uiautomator host wrapper | Search index/repository description inspected; raw/source-tree retrieval failed. No complete source audit. Do not label secure merely because its description says safe. Not chosen over the phone-resident native project. |
| [tanbro/uiautomator2-mcp-server](https://github.com/tanbro/uiautomator2-mcp-server) | Broad uiautomator2/ADB wrapper | README/architecture/requirements inspected, actual server file retrieval failed. Broad tools and host/debugging dependencies need a new on-device policy boundary; not the shortest restricted runtime path. |

## Source-level observations that affected the choice

The native upstream service configures window retrieval/view IDs, foreground
package/activity tracking and an Accessibility input method. It maintains a node
cache with structural-event invalidation and takes display screenshots through
Android's Accessibility API. Its tool-call indicator is non-touchable, which is
useful status but does not itself constitute our required persistent STOP/Pause
control. These observations are from [actual Accessibility service source](https://raw.githubusercontent.com/danielealbano/android-remote-control-mcp/main/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt).

The [actual manifest](https://raw.githubusercontent.com/danielealbano/android-remote-control-mcp/main/app/src/main/AndroidManifest.xml)
declares network, foreground service, boot, app management, camera/audio, location
and storage-related capabilities for the full app. The [build configuration](https://raw.githubusercontent.com/danielealbano/android-remote-control-mcp/main/app/build.gradle.kts)
includes Compose/Hilt/Ktor, native ngrok components and privacy-model dependencies.
Those are broader than the four-primitive pilot and cannot all be built from the
workspace's local cache. This is a scope/build observation, not a claimed vulnerability.

The [NeoAgentman server source](https://raw.githubusercontent.com/NeoAgentman/mobile-use-mcp/main/src/mobile_use_mcp/server.py)
uses semantic selectors and refreshes snapshots around writes. It deliberately
does not echo entered text in successful typing responses. These are worthwhile
patterns, but its initial Android connection remains mediated by a separate
SessionManager/controller backend. None of these source files was copied verbatim
into the pilot.

## Decision

For an eventual general-app controller, adapt the MIT-licensed native project or
its `phone-use` derivative after obtaining an exact source revision and auditing
its authorization, tools, redaction and lifecycle end to end. Prefer removal of
unused tools/permissions, not a cosmetic tool filter. Importing unreviewed, mutable
`main` source into an APK is not part of this handoff.

For the current offline milestone, implement only the narrow native **policy and
sandbox boundary** needed to test secure primitives, and reuse the **installed
official MCP SDK** for protocol/OAuth rather than rebuilding it. This does not
reimplement upstream's browser/gateway, macros, files, camera, location, native
tunneling, privacy model or broad app automation. It is original pilot code, not
a fork or a claim that upstream security is certified. The small no-library
Android source project is buildable by free phone-triggered CI once dependencies
can be fetched there.

No additional project was substantiated as a better secured phone-native replacement
in the accessible source results. A thorough full-source/licensing/revision audit
of all six projects and newer alternatives remains incomplete. This environmental
limit is recorded rather than silently marking it done. The prototype remains
restricted so that incomplete upstream audits do not authorize real-account access.
