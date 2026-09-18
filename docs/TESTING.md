# Scripted client testing

Adapted from Planetary Sable's hidden-client harness. All client tests run as scripted,
hidden, unfocused processes — no desktop mouse/keyboard automation, no window activation.

## Running

```powershell
scripts/Test-ClientSmoke.ps1                    # smoke scenario (default)
scripts/Test-ClientSmoke.ps1 -Scenario editor   # editor/render pipeline probe
scripts/Test-ClientSmoke.ps1 -Scenario replay -ReplayPath <path\to\replay.zip>
```

Each run creates a project-owned artifact directory under `artifacts/` containing the
launch spec, exported argument files, per-process stdout/stderr logs, the client run
directory (options, logs, screenshots), a source manifest with SHA-256 hashes, and a
`result.json` verdict.

## How it works

1. `exportTestLaunches` (Gradle) resolves the `runClient` task and writes
   `client-launch.json` — a standalone command/environment/working-directory spec plus
   snapshotted argument files. `-PflashbackClientScenario=<name>` and
   `-PflashbackClientRunDir=<dir>` configure the run; `-PflashbackLaunchExportDir=<dir>`
   selects the output directory.
2. The runner writes a minimal `options.txt` into the artifact client directory, strips
   `DevAuth` from the exported classpath (it would attempt an interactive login), and
   spawns the client with `CreateNoWindow` and redirected stdio.
3. In-process, `-Dflashback.hiddenClient=true` enables the testing mixins:
   `testing.MixinHiddenWindow` ORs `SDL_WINDOW_HIDDEN` into the window-creation flags and
   blocks fullscreen transitions; `testing.MixinHiddenMouse` makes mouse grab/release a
   logical state without OS cursor capture; `testing.MixinHiddenClient` drives the
   scenario from `Minecraft.tick`/`renderFrame`.
4. `HiddenClientScenario` (com.moulberry.flashback.testing) enforces the hidden-window
   contract every rendered frame via `SDL_GetWindowFlags`, waits for the scenario's
   readiness condition, captures framebuffer evidence via `Screenshot.takeScreenshot`,
   asserts colour variation, writes `hidden-<scenario>.png`, logs `HIDDEN_CLIENT_PASS`,
   and stops the client. Disconnection watchdogs and a tick timeout fail the run.

## Scenarios

- `smoke` — reach the title screen, render 40 frames, screenshot, exit.
- `editor` — title screen, then an editor/render probe that runs without a replay:
  injects key/mouse/text events through the real `KeyboardHandler`/`MouseHandler`
  entry points (exercising the interception mixins and `CustomImGuiImplSdl` routing),
  drives one real ImGui frame through the SDL backend, renders it with the
  `imgui_b3d` SPIR-V pipeline via `imguiRenderer.renderDrawData`, blits the result
  into the main framebuffer, and reads it back through the renderpearl
  `copyTextureToBuffer` path. Asserts ≥500 drawn pixels, writes
  `hidden-editor-probe.png`, logs `HIDDEN_EDITOR_PASS`, then takes the standard
  framebuffer screenshot.
- `replay` — opens a real ServerReplay zip via `Flashback.openReplayWorld`
  (`-ReplayPath` → `-Dflashback.replayPath`), waits for `ReplayUI` activation in
  spectator mode, then injects the `P` pause-keybind press plus scroll/move/
  click/text events through the real `KeyboardHandler`/`MouseHandler` entry
  points while the editor is active. Asserts the full keybind chain end-to-end:
  SDL scancode → `CustomImGuiImplSdl` → ImGui event queue → `Keybinds.PAUSE`
  → `ReplayServer.replayPaused` toggles (`HIDDEN_REPLAY_KEYBIND`). Then reads
  `ReplayUI.compositeOnTop` back through `copyTextureToBuffer`, asserts ≥5000
  drawn pixels, writes `hidden-replay-editor.png`, logs `HIDDEN_REPLAY_PASS`.

New scenarios select via `-PflashbackClientScenario=<name>`; unknown names fail fast.

## Editor-path coverage notes

The `editor` scenario covers backend init, input interception/forwarding, font
upload, shader compilation, vertex/index upload, render pass, and GPU readback.
The `replay` scenario additionally covers `isActiveInternal` gating,
ImGui-capture keybind routing (`P` → `replayPaused`), the `compositeOnTop`
screen composite produced by `drawOverlayInternal`, and real replay load. Still
unexercised: mouse-grab/relative-mode behaviour under an OS cursor (the harness
uses a logical grab), and multi-window edge cases (viewports are disabled).

## Contract

- Hidden and unfocused at all times; fullscreen requests are cancelled.
- No OS-level input events; input replay must drive normal `KeyMapping` state in-process.
- Artifacts stay under the project `artifacts/` directory.
