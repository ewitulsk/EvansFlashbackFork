package com.moulberry.flashback.testing;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FramebufferUtils;
import com.moulberry.flashback.editor.ui.ReplayUI;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.internal.ImGuiContext;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLScancode;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scripted hidden-client scenario driver. Enabled with -Dflashback.hiddenClient=true and a
 * -Dflashback.clientScenario=<name> selection. Runs entirely in-process: no desktop
 * mouse/keyboard automation. Validates the hidden-window contract every rendered frame and
 * captures framebuffer evidence to the run directory before stopping the client.
 */
public final class HiddenClientScenario {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static int ticks, readyTicks;
    private static int disconnectedTicks;
    private static boolean finished;
    private static boolean screenshotRequested;
    private static final AtomicReference<NativeImage> pendingScreenshot = new AtomicReference<>();

    private HiddenClientScenario() {}

    public static boolean enabled() {
        return Boolean.getBoolean("flashback.hiddenClient");
    }

    private static String scenario() {
        return System.getProperty("flashback.clientScenario", "smoke");
    }

    public static void tick() {
        if (!enabled() || finished) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        disconnectedTicks = minecraft.gui.screen() instanceof DisconnectedScreen ? disconnectedTicks + 1 : 0;
        if (disconnectedTicks > 40) {
            throw new IllegalStateException("Hidden test client remained disconnected; see the connection error in its log");
        }
        ticks++;
        String scenario = scenario();
        int limit = 4800;
        if (ticks > limit) {
            throw new IllegalStateException("Hidden client scenario timed out after " + limit + " ticks");
        }
        if (minecraft.gui.screen() instanceof TitleScreen && minecraft.gui.overlay() == null) {
            readyTicks++;
        }

        var image = pendingScreenshot.getAndSet(null);
        if (image != null) {
            finishWithEvidence(minecraft, image, scenario);
            return;
        }
        if (screenshotRequested) {
            return;
        }
        if (scenario.equals("smoke")) {
            if (readyTicks >= 40) {
                screenshotRequested = true;
                Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), pendingScreenshot::set);
            }
        } else if (scenario.equals("editor")) {
            if (!editorProbeIssued && readyTicks >= 40) {
                runEditorProbe(minecraft);
                editorProbeIssued = true;
            }
            if (editorProbeIssued && editorProbeTicks++ > 200) {
                throw new IllegalStateException("Editor probe readback did not complete");
            }
        } else if (scenario.equals("replay")) {
            runReplayScenario(minecraft);
        } else if (scenario.equals("export")) {
            runExportScenario(minecraft);
        } else if (scenario.equals("editordepth")) {
            runEditorDepthScenario(minecraft);
        } else if (scenario.equals("record")) {
            runRecordScenario(minecraft);
        } else {
            throw new IllegalStateException("Unknown client scenario " + scenario);
        }
    }

    private static boolean editorProbeIssued;
    private static int editorProbeTicks;

    /**
     * Drives a real ImGui frame through the SDL backend and B3D renderer while the editor
     * is inactive, then reads the offscreen render target back through the renderpearl
     * copy-texture-to-buffer path. Exercises input interception, font upload, the
     * imgui_b3d SPIR-V pipeline, vertex/index upload, and GPU readback without needing
     * a loaded replay.
     */
    private static void runEditorProbe(Minecraft minecraft) {
        var sdl = ReplayUI.imguiSdl();
        if (!sdl.isReady()) {
            throw new IllegalStateException("SDL ImGui backend did not initialize");
        }
        long window = minecraft.getWindow().handle();

        // Input interception probes: real handler entry points, editor inactive so events
        // must route to the game (keyPressedGame tracks them) and not crash.
        minecraft.keyboardHandler.keyPress(window, 1, new KeyEvent(SDLScancode.SDL_SCANCODE_E, 0, 0));
        minecraft.keyboardHandler.keyPress(window, 0, new KeyEvent(SDLScancode.SDL_SCANCODE_E, 0, 0));
        minecraft.keyboardHandler.textInput(window, "a");
        minecraft.mouseHandler.onButton(window, new MouseButtonInfo(SDLMouse.SDL_BUTTON_LEFT, 0), 1);
        minecraft.mouseHandler.onButton(window, new MouseButtonInfo(SDLMouse.SDL_BUTTON_LEFT, 0), 0);
        minecraft.mouseHandler.onScroll(window, 1.0, 0.5);
        minecraft.mouseHandler.onMove(window, 100.0, 100.0, 5.0, 5.0);
        if (sdl.isDispatchingToGame()) {
            throw new IllegalStateException("dispatchingToGame guard left set after input probe");
        }

        // Drive one real ImGui frame with test content.
        ImGuiContext context = ReplayUI.getImGuiContext();
        long previous = ImGui.getCurrentContext().ptr;
        ImGui.setCurrentContext(context);
        RenderTarget rendered;
        try {
            sdl.newFrame();
            ImGui.newFrame();
            ImGui.setNextWindowPos(20, 20);
            ImGui.setNextWindowSize(420, 320);
            if (ImGui.begin("Flashback Editor Probe")) {
                ImGui.text("Flashback 26.3 editor probe");
                ImGui.button("probe button");
                ImGui.colorButton("probe swatch", new float[]{1f, 0.25f, 0.1f, 1f}, 0, 200, 60);
            }
            ImGui.end();
            ImGui.render();
            var drawData = ImGui.getDrawData();
            if (drawData == null || drawData.getCmdListsCount() <= 0) {
                throw new IllegalStateException("ImGui produced no draw data");
            }
            rendered = ReplayUI.imguiRenderer.renderDrawData(drawData);
        } finally {
            ImGuiContext current = ImGui.getCurrentContext();
            current.ptr = previous;
            ImGui.setCurrentContext(current);
        }
        if (rendered == null || rendered.getColorTexture() == null) {
            throw new IllegalStateException("imguiRenderer.renderDrawData returned no target");
        }

        GpuTexture texture = rendered.getColorTexture();
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);

        // Composite the probe frame into the main framebuffer so the screenshot is evidence.
        FramebufferUtils.blitTo(rendered.getColorTextureView(), minecraft.gameRenderer.mainRenderTarget(),
            0f, 0f, 1f, 1f);

        // Async GPU readback through the same path SaveableFramebuffer uses.
        var device = RenderSystem.getDevice();
        GpuBuffer pbo = device.createBuffer(() -> "flashback editor probe pbo",
            GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 4L * width * height);
        device.createCommandEncoder().copyTextureToBuffer(texture, pbo, 0, () -> {
            try (GpuBufferSlice.MappedView view = pbo.map(true, false)) {
                finishEditorProbe(minecraft, view.data(), width, height);
            } finally {
                pbo.close();
            }
        }, 0);
    }

    private static boolean replayOpened;
    private static int replayActiveTicks;
    private static boolean replayInputSent;
    private static Boolean replayInitialPaused;
    private static int replayProbeTicks;
    private static boolean replayCompositeReadbackIssued;

    /**
     * Opens a real replay zip (-Dflashback.replayPath), waits for the editor to activate
     * in spectator mode, then drives input through the real handler entry points with the
     * editor active: a Flashback keybind key (P) must reach ImGui and toggle replay pause,
     * scroll must accumulate in ImGuiIO, and the per-frame compositeOnTop proves the
     * MixinMinecraft.renderFrame composite wrap is live. The composite texture is read
     * back through copyTextureToBuffer as visual evidence.
     */
    private static void runReplayScenario(Minecraft minecraft) {
        if (!replayOpened) {
            if (readyTicks < 5) {
                return;
            }
            String path = System.getProperty("flashback.replayPath");
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("replay scenario requires -Dflashback.replayPath=<zip>");
            }
            LOGGER.info("HIDDEN_REPLAY_OPEN path={}", path);
            Flashback.openReplayWorld(java.nio.file.Path.of(path));
            replayOpened = true;
            replayOpenedTick = ticks;
            return;
        }

        if (!ReplayUI.isActive()) {
            if (ticks - replayOpenedTick > 2400) {
                throw new IllegalStateException("Replay editor never activated (level/player/spectator/overlay gating)");
            }
            return;
        }

        replayActiveTicks++;

        if (!replayInputSent && replayActiveTicks >= 30) {
            var sdl = ReplayUI.imguiSdl();
            long window = minecraft.getWindow().handle();
            var replayServer = Flashback.getReplayServer();
            if (replayServer == null) {
                throw new IllegalStateException("Editor active but no ReplayServer");
            }
            replayInitialPaused = replayServer.replayPaused;

            ImGuiContext context = ReplayUI.getImGuiContext();
            long previous = ImGui.getCurrentContext().ptr;
            ImGui.setCurrentContext(context);
            try {
                // P is the Flashback PAUSE keybind. ImGui queues key events and applies
                // them on the next NewFrame, so the observable effect is replayPaused
                // toggling inside the next editor frame — asserted below.
                minecraft.keyboardHandler.keyPress(window, 1, new KeyEvent(InputConstants.KEY_P, 0, 0));
                minecraft.mouseHandler.onScroll(window, 0.0, 1.0);
                minecraft.mouseHandler.onMove(window, 400.0, 240.0, 3.0, 2.0);
                minecraft.mouseHandler.onButton(window, new MouseButtonInfo(SDLMouse.SDL_BUTTON_LEFT, 0), 1);
                minecraft.mouseHandler.onButton(window, new MouseButtonInfo(SDLMouse.SDL_BUTTON_LEFT, 0), 0);
                minecraft.keyboardHandler.textInput(window, "x");
                minecraft.keyboardHandler.keyPress(window, 0, new KeyEvent(InputConstants.KEY_P, 0, 0));
            } finally {
                ImGuiContext current = ImGui.getCurrentContext();
                current.ptr = previous;
                ImGui.setCurrentContext(current);
            }
            if (sdl.isDispatchingToGame()) {
                throw new IllegalStateException("dispatchingToGame guard left set after input probe");
            }
            replayInputSent = true;
            return;
        }

        if (replayInputSent && !replayCompositeReadbackIssued) {
            replayProbeTicks++;
            if (replayProbeTicks > 200) {
                throw new IllegalStateException("Replay PAUSE keybind never toggled replayPaused");
            }
            var replayServer = Flashback.getReplayServer();
            if (replayServer == null || replayServer.replayPaused == replayInitialPaused) {
                return; // still waiting for the keybind to fire inside the editor frame
            }
            LOGGER.info("HIDDEN_REPLAY_KEYBIND pause toggled {} -> {}", replayInitialPaused, replayServer.replayPaused);

            // compositeOnTop is set every frame by drawOverlayInternal and consumed by the
            // MixinMinecraft blit wrap — it being non-null here proves the composite path.
            RenderTarget composite = ReplayUI.compositeOnTop;
            if (composite == null || composite.getColorTexture() == null) {
                throw new IllegalStateException("Editor active but compositeOnTop is null");
            }
            GpuTexture texture = composite.getColorTexture();
            int width = texture.getWidth(0);
            int height = texture.getHeight(0);
            var device = RenderSystem.getDevice();
            GpuBuffer pbo = device.createBuffer(() -> "flashback replay composite pbo",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 4L * width * height);
            device.createCommandEncoder().copyTextureToBuffer(texture, pbo, 0, () -> {
                try (GpuBufferSlice.MappedView view = pbo.map(true, false)) {
                    finishReplayProbe(minecraft, view.data(), width, height);
                } finally {
                    pbo.close();
                }
            }, 0);
            replayCompositeReadbackIssued = true;
        }
    }

    private static int replayOpenedTick;

    private static int exportOpenedTick;
    private static boolean exportOpened;
    private static int exportActiveTicks;
    private static boolean exportJobIssued;
    private static int exportWaitTicks;
    private static java.nio.file.Path exportOutput;

    /**
     * Opens the replay, waits for the editor to activate, then queues a real ExportJob
     * (320x180 MP4/H264 + AAC audio) against a short tick range. Setting
     * Flashback.EXPORT_JOB deactivates the editor via isActiveInternal and MixinMinecraft's
     * runTick wrap executes the job on the render thread — exercising the framegraph
     * render path, SaveableFramebuffer readback, the ffmpeg writer, and the SOFT loopback
     * audio device (MixinAudioLibrary). Completion is signalled by EXPORT_JOB clearing.
     */
    private static void runExportScenario(Minecraft minecraft) {
        if (!exportOpened) {
            if (readyTicks < 5) {
                return;
            }
            String path = System.getProperty("flashback.replayPath");
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("export scenario requires -Dflashback.replayPath=<zip>");
            }
            LOGGER.info("HIDDEN_EXPORT_OPEN path={}", path);
            Flashback.openReplayWorld(java.nio.file.Path.of(path));
            exportOpened = true;
            exportOpenedTick = ticks;
            return;
        }

        if (!exportJobIssued) {
            if (!ReplayUI.isActive()) {
                if (ticks - exportOpenedTick > 2400) {
                    throw new IllegalStateException("Export: replay editor never activated");
                }
                return;
            }
            if (++exportActiveTicks < 30) {
                return;
            }

            var replayServer = Flashback.getReplayServer();
            var editorState = com.moulberry.flashback.state.EditorStateManager.getCurrent();
            var player = minecraft.player;
            if (replayServer == null || editorState == null || player == null) {
                throw new IllegalStateException("Export: editor active but replayServer/editorState/player missing");
            }

            int start = 200;
            int end = Math.min(start + 40, replayServer.getTotalReplayTicks());
            editorState.setExportTicks(start, end, replayServer.getTotalReplayTicks());

            exportOutput = minecraft.gameDirectory.toPath().resolve("export-test.mp4");
            var settings = new com.moulberry.flashback.exporting.ExportSettings("export-test", editorState.copy(),
                player.position(), player.getYRot(), player.getXRot(),
                320, 180, start, end,
                com.moulberry.flashback.combo_options.ExportProjection.PERSPECTIVE, 1.0f,
                20.0, false, false,
                com.moulberry.flashback.combo_options.VideoContainer.MP4,
                com.moulberry.flashback.combo_options.VideoCodec.H264,
                com.moulberry.flashback.combo_options.VideoCodec.H264.getEncoders()[0],
                0, false, false, false,
                false, com.moulberry.flashback.combo_options.AudioCodec.AAC,
                exportOutput, null);
            LOGGER.info("HIDDEN_EXPORT_START ticks={}..{} encoder={} output={}", start, end,
                com.moulberry.flashback.combo_options.VideoCodec.H264.getEncoders()[0], exportOutput);
            Flashback.EXPORT_JOB = new com.moulberry.flashback.exporting.ExportJob(settings);
            exportJobIssued = true;
            return;
        }

        if (Flashback.EXPORT_JOB != null) {
            exportWaitTicks = 0;
            return;
        }
        if (++exportWaitTicks < 5) {
            return;
        }

        try {
            long size = java.nio.file.Files.size(exportOutput);
            if (size < 10_000) {
                throw new IllegalStateException("Export output suspiciously small: " + size);
            }
            LOGGER.info("HIDDEN_EXPORT_LOOPBACK used={}", probeLoopbackDevice(minecraft));
            LOGGER.info("HIDDEN_EXPORT_PASS output={} size={}", exportOutput, size);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Export output missing: " + exportOutput, exception);
        }
        screenshotRequested = true;
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), pendingScreenshot::set);
    }

    private static int depthOpenedTick;
    private static boolean depthOpened;
    private static int depthActiveTicks;
    private static int depthPhase;
    private static int depthPhaseTicks;
    private static int depthMarkStart = -1;
    private static int depthMarkEnd = -1;
    private static int depthTrackIndex = -1;
    private static net.minecraft.world.phys.Vec3 depthOriginalPos;
    private static net.minecraft.world.phys.Vec3 depthExpectedPos;
    private static int depthTickBefore;

    /**
     * Exercises editor depth beyond pause: MARK_IN/MARK_OUT keybinds (export range
     * markers on the scene), a camera keyframe track applied through the real
     * applyKeyframes -> MinecraftKeyframeHandler -> player.snapTo path, RightArrow
     * timeline scrubbing through ImGui key events, and Ctrl+Z undo through the
     * keybind layer reverting the scene history.
     */
    private static void runEditorDepthScenario(Minecraft minecraft) {
        var window = minecraft.getWindow().handle();
        if (!depthOpened) {
            if (readyTicks < 5) {
                return;
            }
            String path = System.getProperty("flashback.replayPath");
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("editordepth scenario requires -Dflashback.replayPath=<zip>");
            }
            LOGGER.info("HIDDEN_DEPTH_OPEN path={}", path);
            Flashback.openReplayWorld(java.nio.file.Path.of(path));
            depthOpened = true;
            depthOpenedTick = ticks;
            return;
        }

        var replayServer = Flashback.getReplayServer();
        var editorState = com.moulberry.flashback.state.EditorStateManager.getCurrent();
        if (replayServer == null || editorState == null) {
            return;
        }

        if (!ReplayUI.isActive()) {
            if (depthPhase == 0 && ticks - depthOpenedTick > 2400) {
                throw new IllegalStateException("editordepth: replay editor never activated");
            }
            return;
        }
        if (depthPhase == 0 && ++depthActiveTicks < 30) {
            return;
        }

        if (++depthPhaseTicks > 600) {
            throw new IllegalStateException("editordepth: phase " + depthPhase + " did not complete");
        }

        switch (depthPhase) {
            case 0 -> {
                // Inject MARK_IN (I) then MARK_OUT (O) through the real keyboard path.
                long stamp = editorState.acquireRead();
                try {
                    var scene = editorState.getCurrentScene(stamp);
                    depthMarkStart = scene.exportStartTicks;
                    depthMarkEnd = scene.exportEndTicks;
                } finally {
                    editorState.release(stamp);
                }
                pressKey(minecraft, InputConstants.KEY_I, 0);
                pressKey(minecraft, InputConstants.KEY_O, 0);
                depthPhase = 1;
                depthPhaseTicks = 0;
            }
            case 1 -> {
                if (depthPhaseTicks < 5) return;
                int startTick, endTick;
                long stamp = editorState.acquireWrite();
                try {
                    var scene = editorState.getCurrentScene(stamp);
                    startTick = scene.exportStartTicks;
                    endTick = scene.exportEndTicks;
                    if (startTick < 0 || endTick < 0) {
                        throw new IllegalStateException("MARK_IN/OUT keybinds did not set export ticks: "
                            + startTick + "/" + endTick);
                    }
                    // Build a camera keyframe track: teleport keyframes far from current pos.
                    var player = minecraft.player;
                    depthOriginalPos = player.position();
                    var track = new com.moulberry.flashback.state.KeyframeTrack(
                        com.moulberry.flashback.keyframe.types.CameraKeyframeType.INSTANCE);
                    var base = depthOriginalPos;
                    track.keyframesByTick.put(100, new com.moulberry.flashback.keyframe.impl.CameraKeyframe(
                        new org.joml.Vector3d(base.x, base.y + 60, base.z), 0.0f, -89.0f, 0.0f));
                    track.keyframesByTick.put(200, new com.moulberry.flashback.keyframe.impl.CameraKeyframe(
                        new org.joml.Vector3d(base.x, base.y + 60, base.z), 0.0f, -89.0f, 0.0f));
                    scene.keyframeTracks.add(track);
                    depthTrackIndex = scene.keyframeTracks.size() - 1;
                    // Push a real undo entry through the same API the timeline uses.
                    scene.setKeyframe(depthTrackIndex, 300, new com.moulberry.flashback.keyframe.impl.CameraKeyframe(
                        new org.joml.Vector3d(base.x, base.y + 60, base.z), 0.0f, -89.0f, 0.0f));
                    depthExpectedPos = new net.minecraft.world.phys.Vec3(base.x, base.y + 60, base.z);
                } finally {
                    editorState.release(stamp);
                }
                LOGGER.info("HIDDEN_DEPTH_MARKERS start={} end={}", startTick, endTick);
                editorState.markDirty();
                replayServer.goToReplayTick(150);
                depthPhase = 2;
                depthPhaseTicks = 0;
            }
            case 2 -> {
                // goToReplayTick is asynchronous — wait until the replay has
                // actually reached the keyframe range before forcing an apply.
                if (replayServer.getReplayTick() < 140) return;
                // Pause playback so recorded position packets can't overwrite
                // the keyframe-applied position before we can observe it.
                replayServer.replayPaused = true;
                replayServer.forceApplyKeyframes.set(true);
                // Also apply directly on this thread — the same call runTick makes
                // — so a broken flag chain can't mask the keyframe machinery.
                com.moulberry.flashback.state.EditorStateManager.get(replayServer.getMetadata().replayIdentifier)
                    .applyKeyframes(new com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler(minecraft), 150f);
                depthPhase = 3;
                depthPhaseTicks = 0;
            }
            case 3 -> {
                var pos = minecraft.player.position();
                if (depthPhaseTicks % 10 == 0) {
                    LOGGER.info("HIDDEN_DEPTH_DIAG pos={} paused={} targetTick={} partial={}",
                        pos, replayServer.replayPaused, replayServer.getReplayTick(),
                        replayServer.getPartialReplayTick());
                }
                if (depthPhaseTicks < 15) return;
                if (Math.abs(pos.y - depthExpectedPos.y) > 5.0) {
                    throw new IllegalStateException("Camera keyframe did not move player: expected y~"
                        + depthExpectedPos.y + " got " + pos);
                }
                LOGGER.info("HIDDEN_DEPTH_KEYFRAME moved {} -> {}", depthOriginalPos, pos);
                replayServer.replayPaused = false;
                // RightArrow timeline scrub through ImGui.
                depthTickBefore = replayServer.getReplayTick();
                pressKey(minecraft, InputConstants.KEY_RIGHT, 0);
                depthPhase = 4;
                depthPhaseTicks = 0;
            }
            case 4 -> {
                if (depthPhaseTicks < 10) return;
                int now = replayServer.getReplayTick();
                if (now <= depthTickBefore) {
                    throw new IllegalStateException("RightArrow did not scrub the timeline: " + depthTickBefore + " -> " + now);
                }
                LOGGER.info("HIDDEN_DEPTH_SCRUB tick {} -> {}", depthTickBefore, now);
                // Ctrl+Z: press Z down through the real keyboard path, then emit
                // ModCtrl/LeftCtrl through io — keyCallback's updateKeyModifiers
                // emits Mod*=false events from the real keyboard ahead of Z in
                // the queue, so the mod-down events must land AFTER Z-down to be
                // live when the Z pressed-edge frame processes. Release Z through
                // the real path, then release the mods — exercises
                // Keybinds.UNDO -> editorScene.undo end to end.
                long window2 = minecraft.getWindow().handle();
                var io = ReplayUI.getIO();
                ImGuiContext ctx2 = ReplayUI.getImGuiContext();
                long prevCtx = ImGui.getCurrentContext().ptr;
                ImGui.setCurrentContext(ctx2);
                try {
                    minecraft.keyboardHandler.keyPress(window2, 1, new KeyEvent(InputConstants.KEY_Z, 0, InputConstants.MOD_CONTROL));
                    io.addKeyEvent(imgui.moulberry90.flag.ImGuiKey.ModCtrl, true);
                    io.addKeyEvent(imgui.moulberry90.flag.ImGuiKey.LeftCtrl, true);
                    minecraft.keyboardHandler.keyPress(window2, 0, new KeyEvent(InputConstants.KEY_Z, 0, InputConstants.MOD_CONTROL));
                    io.addKeyEvent(imgui.moulberry90.flag.ImGuiKey.ModCtrl, false);
                    io.addKeyEvent(imgui.moulberry90.flag.ImGuiKey.LeftCtrl, false);
                } finally {
                    ImGuiContext current = ImGui.getCurrentContext();
                    current.ptr = prevCtx;
                    ImGui.setCurrentContext(current);
                }
                depthPhase = 5;
                depthPhaseTicks = 0;
            }
            case 5 -> {
                if (depthPhaseTicks < 10) return;
                boolean stillThere;
                long stamp = editorState.acquireRead();
                try {
                    var scene = editorState.getCurrentScene(stamp);
                    stillThere = scene.keyframeTracks.get(depthTrackIndex).keyframesByTick.containsKey(300);
                } finally {
                    editorState.release(stamp);
                }
                if (stillThere) {
                    throw new IllegalStateException("Ctrl+Z did not undo the setKeyframe action: keyframe@300 still present");
                }
                LOGGER.info("HIDDEN_DEPTH_UNDO keyframe@300 removed by undo");
                LOGGER.info("HIDDEN_DEPTH_PASS markers keyframe scrub undo");
                screenshotRequested = true;
                Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), pendingScreenshot::set);
                depthPhase = 6;
            }
        }
    }

    private static int recOpenedTick;
    private static boolean recOpened;
    private static boolean recStarted;
    private static int recStartTicks;
    private static java.nio.file.Path recReplayDir;

    /**
     * Exercises the recording path: during live replay playback the client is
     * connected to the integrated ReplayServer, so packets flow through the same
     * ClientPacketListener/Connection the Recorder taps (MixinConnection) — this
     * captures serialization, tick bookkeeping, and ReplayExporter zip writing
     * exactly as a live record would. Starts the recorder, lets playback run,
     * finishes with quicksave enabled, then asserts the exported zip exists.
     */
    private static void runRecordScenario(Minecraft minecraft) {
        if (!recOpened) {
            if (readyTicks < 5) {
                return;
            }
            String path = System.getProperty("flashback.replayPath");
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("record scenario requires -Dflashback.replayPath=<zip>");
            }
            LOGGER.info("HIDDEN_RECORD_OPEN path={}", path);
            Flashback.openReplayWorld(java.nio.file.Path.of(path));
            recOpened = true;
            recOpenedTick = ticks;
            return;
        }

        var replayServer = Flashback.getReplayServer();
        if (replayServer == null) {
            if (ticks - recOpenedTick > 2400) {
                throw new IllegalStateException("record: replay never loaded");
            }
            return;
        }

        if (!recStarted) {
            if (minecraft.player == null) {
                return;
            }
            Flashback.getConfig().recordingControls.quicksave = true;
            Flashback.startRecordingReplay();
            if (Flashback.RECORDER == null) {
                throw new IllegalStateException("record: startRecordingReplay produced no recorder");
            }
            recReplayDir = Flashback.getReplayFolder();
            recStarted = true;
            recStartTicks = ticks;
            // Unpause so playback (and therefore packets) actually advance.
            replayServer.replayPaused = false;
            LOGGER.info("HIDDEN_RECORD_START dir={}", recReplayDir);
            return;
        }

        if (ticks - recStartTicks < 240) {
            return;
        }

        java.io.File[] before = recReplayDir.toFile().listFiles((d, n) -> n.endsWith(".zip"));
        int countBefore = before == null ? 0 : before.length;
        Flashback.finishRecordingReplay();
        if (Flashback.RECORDER != null) {
            throw new IllegalStateException("record: recorder still set after finish");
        }

        // The export is async — wait briefly for the zip to land.
        java.io.File newest = null;
        for (int i = 0; i < 200; i++) {
            java.io.File[] now = recReplayDir.toFile().listFiles((d, n) -> n.endsWith(".zip"));
            if (now != null && now.length > countBefore) {
                newest = java.util.Arrays.stream(now)
                    .max(java.util.Comparator.comparingLong(java.io.File::lastModified)).orElse(null);
                break;
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        if (newest == null || newest.length() < 10_000) {
            throw new IllegalStateException("record: no replay zip exported to " + recReplayDir);
        }
        LOGGER.info("HIDDEN_RECORD_PASS zip={} size={}", newest.getName(), newest.length());
        screenshotRequested = true;
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), pendingScreenshot::set);
    }

    private static void pressKey(Minecraft minecraft, int key, int modifiers) {
        long window = minecraft.getWindow().handle();
        ImGuiContext context = ReplayUI.getImGuiContext();
        long previous = ImGui.getCurrentContext().ptr;
        ImGui.setCurrentContext(context);
        try {
            minecraft.keyboardHandler.keyPress(window, 1, new KeyEvent(key, 0, modifiers));
            minecraft.keyboardHandler.keyPress(window, 0, new KeyEvent(key, 0, modifiers));
        } finally {
            ImGuiContext current = ImGui.getCurrentContext();
            current.ptr = previous;
            ImGui.setCurrentContext(current);
        }
    }

    private static String probeLoopbackDevice(Minecraft minecraft) {
        try {
            var soundEngineField = net.minecraft.client.sounds.SoundManager.class.getDeclaredField("soundEngine");
            soundEngineField.setAccessible(true);
            Object soundEngine = soundEngineField.get(minecraft.getSoundManager());
            var libraryField = soundEngine.getClass().getDeclaredField("library");
            libraryField.setAccessible(true);
            Object library = libraryField.get(soundEngine);
            var loopbackField = library.getClass().getDeclaredField("usingLoopbackDevice");
            loopbackField.setAccessible(true);
            return String.valueOf(loopbackField.getBoolean(library));
        } catch (ReflectiveOperationException exception) {
            return "unknown:" + exception.getClass().getSimpleName();
        }
    }

    private static void finishReplayProbe(Minecraft minecraft, java.nio.ByteBuffer data, int width, int height) {
        int drawn = 0;
        try (NativeImage image = new NativeImage(width, height, false)) {
            for (int y = 0; y < height; y++) {
                int srcY = height - 1 - y;
                for (int x = 0; x < width; x++) {
                    int i = (srcY * width + x) * 4;
                    int r = data.get(i) & 0xFF;
                    int g = data.get(i + 1) & 0xFF;
                    int b = data.get(i + 2) & 0xFF;
                    int a = data.get(i + 3) & 0xFF;
                    if (a > 0) drawn++;
                    image.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            if (drawn < 5000) {
                throw new IllegalStateException("Editor composite drew almost nothing: " + drawn + " pixels");
            }
            image.writeToFile(minecraft.gameDirectory.toPath().resolve("hidden-replay-editor.png").toFile());
            LOGGER.info("HIDDEN_REPLAY_PASS framebuffer={}x{} drawnPixels={} activeTicks={}", width, height, drawn, replayActiveTicks);
        } catch (IOException exception) {
            throw new IllegalStateException("Replay editor screenshot write failed", exception);
        }
        screenshotRequested = true;
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), pendingScreenshot::set);
    }

    private static void finishEditorProbe(Minecraft minecraft, java.nio.ByteBuffer data, int width, int height) {
        int drawn = 0;
        try (NativeImage image = new NativeImage(width, height, false)) {
            for (int y = 0; y < height; y++) {
                // GL readback rows are bottom-up; flip so the artifact reads top-down.
                int srcY = height - 1 - y;
                for (int x = 0; x < width; x++) {
                    int i = (srcY * width + x) * 4;
                    int r = data.get(i) & 0xFF;
                    int g = data.get(i + 1) & 0xFF;
                    int b = data.get(i + 2) & 0xFF;
                    int a = data.get(i + 3) & 0xFF;
                    if (a > 0) drawn++;
                    image.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            if (drawn < 500) {
                throw new IllegalStateException("Editor probe drew almost nothing: " + drawn + " pixels");
            }
            image.writeToFile(minecraft.gameDirectory.toPath().resolve("hidden-editor-probe.png").toFile());
            LOGGER.info("HIDDEN_EDITOR_PASS framebuffer={}x{} drawnPixels={}", width, height, drawn);
        } catch (IOException exception) {
            throw new IllegalStateException("Editor probe screenshot write failed", exception);
        }
        screenshotRequested = true;
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), pendingScreenshot::set);
    }

    private static void finishWithEvidence(Minecraft minecraft, NativeImage image, String scenario) {
        try (image) {
            var colors = new HashSet<Integer>();
            for (int x = 0; x < image.getWidth(); x += 8) {
                for (int y = 0; y < image.getHeight(); y += 8) {
                    colors.add(image.getPixel(x, y));
                }
            }
            if (colors.size() < 32) {
                throw new IllegalStateException("Insufficient rendered framebuffer variation: " + colors.size());
            }
            image.writeToFile(minecraft.gameDirectory.toPath().resolve("hidden-" + scenario + ".png").toFile());
            LOGGER.info("HIDDEN_CLIENT_PASS scenario={} readyTicks={} visible=false focused=false framebuffer={}x{} colors={}",
                scenario, readyTicks, image.getWidth(), image.getHeight(), colors.size());
        } catch (IOException exception) {
            throw new IllegalStateException("Screenshot write failed", exception);
        }
        finished = true;
        minecraft.stop();
    }

    public static void frame() {
        if (!enabled() || finished) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        long flags = SDLVideo.SDL_GetWindowFlags(minecraft.getWindow().handle());
        if ((flags & SDLVideo.SDL_WINDOW_HIDDEN) == 0
            || (flags & (SDLVideo.SDL_WINDOW_INPUT_FOCUS | SDLVideo.SDL_WINDOW_MOUSE_FOCUS)) != 0) {
            throw new IllegalStateException("Hidden client visibility/focus contract violated");
        }
    }
}
