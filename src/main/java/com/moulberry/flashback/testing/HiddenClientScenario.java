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
