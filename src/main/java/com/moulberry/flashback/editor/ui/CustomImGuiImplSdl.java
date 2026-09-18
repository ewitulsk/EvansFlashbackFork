package com.moulberry.flashback.editor.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.keybinds.Keybind;
import com.moulberry.flashback.editor.keybinds.Keybinds;
import com.moulberry.flashback.exporting.AsyncFileDialogs;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.ImGuiIO;
import imgui.moulberry90.ImGuiPlatformIO;
import imgui.moulberry90.ImGuiViewport;
import imgui.moulberry90.ImVec2;
import imgui.moulberry90.callback.ImStrConsumer;
import imgui.moulberry90.callback.ImStrSupplier;
import imgui.moulberry90.flag.ImGuiBackendFlags;
import imgui.moulberry90.flag.ImGuiConfigFlags;
import imgui.moulberry90.flag.ImGuiKey;
import imgui.moulberry90.flag.ImGuiMouseButton;
import imgui.moulberry90.flag.ImGuiMouseCursor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.PreeditEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.sdl.SDL_Rect;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

import static org.lwjgl.sdl.SDLGamepad.*;
import static org.lwjgl.sdl.SDLKeycode.*;
import static org.lwjgl.sdl.SDLKeyboard.*;
import static org.lwjgl.sdl.SDLMouse.*;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLScancode.*;
import static org.lwjgl.sdl.SDLTimer.*;
import static org.lwjgl.sdl.SDLVideo.*;

/**
 * SDL3-based ImGui platform backend. Replaces the GLFW backend which cannot exist on
 * Minecraft 26.3 (GLFW was removed from the runtime classpath in favour of SDL).
 *
 * Instead of chaining window callbacks like the GLFW backend did, input events are
 * intercepted by mixins ({@code MixinKeyboardHandler}, {@code MixinMouseHandler}) which
 * invoke the {@code *Callback} methods here and then cancel the vanilla call. When the
 * editor decides an event belongs to the game it re-invokes the vanilla handler under
 * the {@link #dispatchingToGame} guard, which the mixins pass through unintercepted.
 */
public class CustomImGuiImplSdl {
    private static final String OS = System.getProperty("os.name", "generic").toLowerCase();
    public static final boolean IS_WINDOWS = OS.contains("win");
    protected static final boolean IS_APPLE = OS.contains("mac") || OS.contains("darwin");

    // SDL window pointer
    private long mainWindowPtr;
    private boolean ready = false;

    // Window/framebuffer sizes for newFrame
    private final IntBuffer winWidth;
    private final IntBuffer winHeight;
    private final IntBuffer fbWidth;
    private final IntBuffer fbHeight;
    private final FloatBuffer mouseXF;
    private final FloatBuffer mouseYF;

    // Mouse cursors provided by SDL
    private final long[] mouseCursors = new long[ImGuiMouseCursor.COUNT];

    // Input routing state (indices are SDL scancodes)
    private final boolean[] keyOwnedByImGui = new boolean[SDL_SCANCODE_COUNT];
    private final boolean[] keyPressedGame = new boolean[SDL_SCANCODE_COUNT];

    // For mouse tracking
    private final boolean[] mouseJustPressed = new boolean[ImGuiMouseButton.COUNT];

    // Internal data
    private double time = 0.0;
    private boolean wantUpdateMonitors = true;
    private int monitorRefreshCountdown = 0;
    private boolean dispatchingToGame = false;
    private boolean lastWindowFocused = true;
    private boolean lastWantTextInput = false;

    private MouseHandledBy grabbed = null;
    private int ignoreMouseMovements = 0;

    private boolean releasedAllKeysBecauseOfDialog = false;
    private boolean releasedAllKeysBecauseOfDisable = false;
    private double grabbedOriginalMouseX;
    private double grabbedOriginalMouseY;
    private int grabLinkedKey = -1;
    private boolean releaseGrabOnUp = false;

    private long gamepad = 0;
    private int gamepadId = -1;

    public double rawMouseX;
    public double rawMouseY;

    public float contentScale = 1.0f;

    public CustomImGuiImplSdl() {
        this.winWidth = BufferUtils.createIntBuffer(1);
        this.winHeight = BufferUtils.createIntBuffer(1);
        this.fbWidth = BufferUtils.createIntBuffer(1);
        this.fbHeight = BufferUtils.createIntBuffer(1);
        this.mouseXF = BufferUtils.createFloatBuffer(1);
        this.mouseYF = BufferUtils.createFloatBuffer(1);
    }

    public boolean isReady() {
        return this.ready;
    }

    /**
     * True while this backend is re-invoking a vanilla input handler on behalf of an
     * event it intercepted. Mixins must not intercept that nested call.
     */
    public boolean isDispatchingToGame() {
        return this.dispatchingToGame;
    }

    public enum MouseHandledBy {
        EDITOR_GRABBED,
        IMGUI,
        GAME,
        BOTH;

        public boolean allowImgui() {
            return this == IMGUI || this == BOTH;
        }

        public boolean allowGame() {
            return this == GAME || this == BOTH;
        }
    }

    public MouseHandledBy getMouseHandledBy() {
        if (!ReplayUI.isActive()) return MouseHandledBy.GAME;
        if (this.grabbed != null) return this.grabbed;
        if (ReplayUI.getIO().getWantCaptureMouse()) return MouseHandledBy.IMGUI;
        return MouseHandledBy.BOTH;
    }

    public boolean isGrabbed() {
        return this.grabbed != null;
    }

    public void ungrab() {
        if (this.grabbed == null) return;
        this.grabbed = null;
        this.grabLinkedKey = 0;

        SDL_SetWindowRelativeMouseMode(this.mainWindowPtr, false);
        SDL_WarpMouseInWindow(this.mainWindowPtr, (float) this.grabbedOriginalMouseX, (float) this.grabbedOriginalMouseY);
    }

    public void setGrabbed(boolean passthroughToGame, int grabLinkedKey, boolean releaseGrabOnUp, double x, double y) {
        if (grabLinkedKey != 0) {
            if (grabLinkedKey < 0) {
                if (!isSdlMouseButtonDown(-grabLinkedKey - 1)) {
                    this.ungrab();
                    return;
                }
            } else if (!InputConstants.isKeyDown(grabLinkedKey)) {
                this.ungrab();
                return;
            }
        }
        if (this.grabbed != null) return;
        this.grabbed = passthroughToGame ? MouseHandledBy.GAME : MouseHandledBy.EDITOR_GRABBED;

        if (grabLinkedKey != 0) {
            this.grabLinkedKey = grabLinkedKey;
            this.releaseGrabOnUp = releaseGrabOnUp;
        }
        if (x >= 0 && y >= 0) {
            this.grabbedOriginalMouseX = x;
            this.grabbedOriginalMouseY = y;
        } else {
            SDL_GetMouseState(this.mouseXF, this.mouseYF);
            this.grabbedOriginalMouseX = this.mouseXF.get(0);
            this.grabbedOriginalMouseY = this.mouseYF.get(0);
        }
        SDL_SetWindowRelativeMouseMode(this.mainWindowPtr, true);
        this.ignoreMouseMovements = 2;
        Minecraft.getInstance().mouseHandler.setIgnoreFirstMove();
    }

    private static double grabbedLastMouseX = 0;
    private static double grabbedLastMouseY = 0;
    private static double grabbedCurrMouseX = 0;
    private static double grabbedCurrMouseY = 0;

    public double getGrabbedMouseDeltaX() {
        double delta = grabbedCurrMouseX - grabbedLastMouseX;
        grabbedLastMouseX = grabbedCurrMouseX;
        return delta;
    }

    public double getGrabbedMouseDeltaY() {
        double delta = grabbedCurrMouseY - grabbedLastMouseY;
        grabbedLastMouseY = grabbedCurrMouseY;
        return delta;
    }

    public static int sdlKeyToImGuiKey(final int scancode) {
        return switch (scancode) {
            case SDL_SCANCODE_TAB -> ImGuiKey.Tab;
            case SDL_SCANCODE_LEFT -> ImGuiKey.LeftArrow;
            case SDL_SCANCODE_RIGHT -> ImGuiKey.RightArrow;
            case SDL_SCANCODE_UP -> ImGuiKey.UpArrow;
            case SDL_SCANCODE_DOWN -> ImGuiKey.DownArrow;
            case SDL_SCANCODE_PAGEUP -> ImGuiKey.PageUp;
            case SDL_SCANCODE_PAGEDOWN -> ImGuiKey.PageDown;
            case SDL_SCANCODE_HOME -> ImGuiKey.Home;
            case SDL_SCANCODE_END -> ImGuiKey.End;
            case SDL_SCANCODE_INSERT -> ImGuiKey.Insert;
            case SDL_SCANCODE_DELETE -> ImGuiKey.Delete;
            case SDL_SCANCODE_BACKSPACE -> ImGuiKey.Backspace;
            case SDL_SCANCODE_SPACE -> ImGuiKey.Space;
            case SDL_SCANCODE_RETURN, SDL_SCANCODE_RETURN2 -> ImGuiKey.Enter;
            case SDL_SCANCODE_ESCAPE -> ImGuiKey.Escape;
            case SDL_SCANCODE_APOSTROPHE -> ImGuiKey.Apostrophe;
            case SDL_SCANCODE_COMMA -> ImGuiKey.Comma;
            case SDL_SCANCODE_MINUS -> ImGuiKey.Minus;
            case SDL_SCANCODE_PERIOD -> ImGuiKey.Period;
            case SDL_SCANCODE_SLASH -> ImGuiKey.Slash;
            case SDL_SCANCODE_SEMICOLON -> ImGuiKey.Semicolon;
            case SDL_SCANCODE_EQUALS -> ImGuiKey.Equal;
            case SDL_SCANCODE_LEFTBRACKET -> ImGuiKey.LeftBracket;
            case SDL_SCANCODE_BACKSLASH -> ImGuiKey.Backslash;
            case SDL_SCANCODE_RIGHTBRACKET -> ImGuiKey.RightBracket;
            case SDL_SCANCODE_GRAVE -> ImGuiKey.GraveAccent;
            case SDL_SCANCODE_CAPSLOCK -> ImGuiKey.CapsLock;
            case SDL_SCANCODE_SCROLLLOCK -> ImGuiKey.ScrollLock;
            case SDL_SCANCODE_NUMLOCKCLEAR -> ImGuiKey.NumLock;
            case SDL_SCANCODE_PRINTSCREEN -> ImGuiKey.PrintScreen;
            case SDL_SCANCODE_PAUSE -> ImGuiKey.Pause;
            case SDL_SCANCODE_KP_0 -> ImGuiKey.Keypad0;
            case SDL_SCANCODE_KP_1 -> ImGuiKey.Keypad1;
            case SDL_SCANCODE_KP_2 -> ImGuiKey.Keypad2;
            case SDL_SCANCODE_KP_3 -> ImGuiKey.Keypad3;
            case SDL_SCANCODE_KP_4 -> ImGuiKey.Keypad4;
            case SDL_SCANCODE_KP_5 -> ImGuiKey.Keypad5;
            case SDL_SCANCODE_KP_6 -> ImGuiKey.Keypad6;
            case SDL_SCANCODE_KP_7 -> ImGuiKey.Keypad7;
            case SDL_SCANCODE_KP_8 -> ImGuiKey.Keypad8;
            case SDL_SCANCODE_KP_9 -> ImGuiKey.Keypad9;
            case SDL_SCANCODE_KP_PERIOD -> ImGuiKey.KeypadDecimal;
            case SDL_SCANCODE_KP_DIVIDE -> ImGuiKey.KeypadDivide;
            case SDL_SCANCODE_KP_MULTIPLY -> ImGuiKey.KeypadMultiply;
            case SDL_SCANCODE_KP_MINUS -> ImGuiKey.KeypadSubtract;
            case SDL_SCANCODE_KP_PLUS -> ImGuiKey.KeypadAdd;
            case SDL_SCANCODE_KP_ENTER -> ImGuiKey.KeypadEnter;
            case SDL_SCANCODE_KP_EQUALS -> ImGuiKey.KeypadEqual;
            case SDL_SCANCODE_LSHIFT -> ImGuiKey.LeftShift;
            case SDL_SCANCODE_LCTRL -> ImGuiKey.LeftCtrl;
            case SDL_SCANCODE_LALT -> ImGuiKey.LeftAlt;
            case SDL_SCANCODE_LGUI -> ImGuiKey.LeftSuper;
            case SDL_SCANCODE_RSHIFT -> ImGuiKey.RightShift;
            case SDL_SCANCODE_RCTRL -> ImGuiKey.RightCtrl;
            case SDL_SCANCODE_RALT -> ImGuiKey.RightAlt;
            case SDL_SCANCODE_RGUI -> ImGuiKey.RightSuper;
            case SDL_SCANCODE_APPLICATION, SDL_SCANCODE_MENU -> ImGuiKey.Menu;
            case SDL_SCANCODE_0 -> ImGuiKey._0;
            case SDL_SCANCODE_1 -> ImGuiKey._1;
            case SDL_SCANCODE_2 -> ImGuiKey._2;
            case SDL_SCANCODE_3 -> ImGuiKey._3;
            case SDL_SCANCODE_4 -> ImGuiKey._4;
            case SDL_SCANCODE_5 -> ImGuiKey._5;
            case SDL_SCANCODE_6 -> ImGuiKey._6;
            case SDL_SCANCODE_7 -> ImGuiKey._7;
            case SDL_SCANCODE_8 -> ImGuiKey._8;
            case SDL_SCANCODE_9 -> ImGuiKey._9;
            case SDL_SCANCODE_A -> ImGuiKey.A;
            case SDL_SCANCODE_B -> ImGuiKey.B;
            case SDL_SCANCODE_C -> ImGuiKey.C;
            case SDL_SCANCODE_D -> ImGuiKey.D;
            case SDL_SCANCODE_E -> ImGuiKey.E;
            case SDL_SCANCODE_F -> ImGuiKey.F;
            case SDL_SCANCODE_G -> ImGuiKey.G;
            case SDL_SCANCODE_H -> ImGuiKey.H;
            case SDL_SCANCODE_I -> ImGuiKey.I;
            case SDL_SCANCODE_J -> ImGuiKey.J;
            case SDL_SCANCODE_K -> ImGuiKey.K;
            case SDL_SCANCODE_L -> ImGuiKey.L;
            case SDL_SCANCODE_M -> ImGuiKey.M;
            case SDL_SCANCODE_N -> ImGuiKey.N;
            case SDL_SCANCODE_O -> ImGuiKey.O;
            case SDL_SCANCODE_P -> ImGuiKey.P;
            case SDL_SCANCODE_Q -> ImGuiKey.Q;
            case SDL_SCANCODE_R -> ImGuiKey.R;
            case SDL_SCANCODE_S -> ImGuiKey.S;
            case SDL_SCANCODE_T -> ImGuiKey.T;
            case SDL_SCANCODE_U -> ImGuiKey.U;
            case SDL_SCANCODE_V -> ImGuiKey.V;
            case SDL_SCANCODE_W -> ImGuiKey.W;
            case SDL_SCANCODE_X -> ImGuiKey.X;
            case SDL_SCANCODE_Y -> ImGuiKey.Y;
            case SDL_SCANCODE_Z -> ImGuiKey.Z;
            case SDL_SCANCODE_F1 -> ImGuiKey.F1;
            case SDL_SCANCODE_F2 -> ImGuiKey.F2;
            case SDL_SCANCODE_F3 -> ImGuiKey.F3;
            case SDL_SCANCODE_F4 -> ImGuiKey.F4;
            case SDL_SCANCODE_F5 -> ImGuiKey.F5;
            case SDL_SCANCODE_F6 -> ImGuiKey.F6;
            case SDL_SCANCODE_F7 -> ImGuiKey.F7;
            case SDL_SCANCODE_F8 -> ImGuiKey.F8;
            case SDL_SCANCODE_F9 -> ImGuiKey.F9;
            case SDL_SCANCODE_F10 -> ImGuiKey.F10;
            case SDL_SCANCODE_F11 -> ImGuiKey.F11;
            case SDL_SCANCODE_F12 -> ImGuiKey.F12;
            case SDL_SCANCODE_F13 -> ImGuiKey.F13;
            case SDL_SCANCODE_F14 -> ImGuiKey.F14;
            case SDL_SCANCODE_F15 -> ImGuiKey.F15;
            case SDL_SCANCODE_F16 -> ImGuiKey.F16;
            case SDL_SCANCODE_F17 -> ImGuiKey.F17;
            case SDL_SCANCODE_F18 -> ImGuiKey.F18;
            case SDL_SCANCODE_F19 -> ImGuiKey.F19;
            case SDL_SCANCODE_F20 -> ImGuiKey.F20;
            case SDL_SCANCODE_F21 -> ImGuiKey.F21;
            case SDL_SCANCODE_F22 -> ImGuiKey.F22;
            case SDL_SCANCODE_F23 -> ImGuiKey.F23;
            case SDL_SCANCODE_F24 -> ImGuiKey.F24;
            default -> ImGuiKey.None;
        };
    }

    protected void updateKeyModifiers() {
        final ImGuiIO io = ReplayUI.getIO();
        int mod = SDL_GetModState();
        io.addKeyEvent(ImGuiKey.ModCtrl, (mod & SDL_KMOD_CTRL) != 0);
        io.addKeyEvent(ImGuiKey.ModShift, (mod & SDL_KMOD_SHIFT) != 0);
        io.addKeyEvent(ImGuiKey.ModAlt, (mod & SDL_KMOD_ALT) != 0);
        io.addKeyEvent(ImGuiKey.ModSuper, (mod & SDL_KMOD_GUI) != 0);
    }

    /**
     * Release all modifier keys via AddKeyEvent only. Never use io.setKeyXxx
     * here — the legacy KeysDown[] path must not be mixed with AddKeyEvent
     * or Dear ImGui asserts ("Backend needs to either only use
     * io.AddKeyEvent() ... Not both!").
     */
    private void clearModifierKeys(ImGuiIO io) {
        io.addKeyEvent(ImGuiKey.ModCtrl, false);
        io.addKeyEvent(ImGuiKey.ModShift, false);
        io.addKeyEvent(ImGuiKey.ModAlt, false);
        io.addKeyEvent(ImGuiKey.ModSuper, false);
    }

    private boolean isSdlMouseButtonDown(int button) {
        return (SDL_GetMouseState(this.mouseXF, this.mouseYF) & (1 << (button - 1))) != 0;
    }

    private void forwardMouseButtonToGame(long window, MouseButtonInfo info, int action) {
        this.dispatchingToGame = true;
        try {
            Minecraft.getInstance().mouseHandler.onButton(window, info, action);
        } finally {
            this.dispatchingToGame = false;
        }
    }

    private void forwardScrollToGame(long window, double xOffset, double yOffset) {
        this.dispatchingToGame = true;
        try {
            Minecraft.getInstance().mouseHandler.onScroll(window, xOffset, yOffset);
        } finally {
            this.dispatchingToGame = false;
        }
    }

    private void forwardMouseMoveToGame(long window, double x, double y, double xrel, double yrel) {
        this.dispatchingToGame = true;
        try {
            Minecraft.getInstance().mouseHandler.onMove(window, x, y, xrel, yrel);
        } finally {
            this.dispatchingToGame = false;
        }
    }

    private void forwardKeyToGame(long window, int action, KeyEvent event) {
        this.dispatchingToGame = true;
        try {
            Minecraft.getInstance().keyboardHandler.keyPress(window, action, event);
        } finally {
            this.dispatchingToGame = false;
        }
    }

    private void forwardTextInputToGame(long window, String text) {
        this.dispatchingToGame = true;
        try {
            Minecraft.getInstance().keyboardHandler.textInput(window, text);
        } finally {
            this.dispatchingToGame = false;
        }
    }

    private void forwardTextEditingToGame(long window, PreeditEvent event) {
        this.dispatchingToGame = true;
        try {
            Minecraft.getInstance().keyboardHandler.textEditing(window, event);
        } finally {
            this.dispatchingToGame = false;
        }
    }

    /**
     * Called from {@code MixinMouseHandler} at the head of {@code MouseHandler.onButton}.
     * The vanilla call is always cancelled afterwards; if the event belongs to the game
     * it is re-dispatched here under the {@link #dispatchingToGame} guard.
     */
    public void mouseButtonCallback(final long window, final MouseButtonInfo info, final int action) {
        if (AsyncFileDialogs.hasDialog()) return;

        final int button = info.button();
        final int mods = info.modifiers();

        if (!ReplayUI.isActive()) {
            this.forwardMouseButtonToGame(window, info, action);
            return;
        }

        updateKeyModifiers();

        if (this.grabbed != null && this.grabLinkedKey < 0 && button == -this.grabLinkedKey - 1) {
            if ((action == 0) == this.releaseGrabOnUp) {
                this.ungrab();
            }
        }

        MouseHandledBy handledBy = this.getMouseHandledBy();

        if (handledBy.allowGame()) {
            this.forwardMouseButtonToGame(window, info, action);
        }
        // SDL button order is left/middle/right, ImGui order is left/right/middle
        int imguiButton = button == 3 ? ImGuiMouseButton.Right : button == 2 ? ImGuiMouseButton.Middle : button - 1;
        if (handledBy.allowImgui() && action == 1 && imguiButton >= 0 && imguiButton < this.mouseJustPressed.length) {
            this.mouseJustPressed[imguiButton] = true;
        }
    }

    /**
     * Called from {@code MixinMouseHandler} at the head of {@code MouseHandler.onScroll}.
     */
    public void scrollCallback(final long window, final double xOffset, final double yOffset) {
        if (AsyncFileDialogs.hasDialog()) return;

        if (ReplayUI.isActive()) {
            var io = ReplayUI.getIO();
            io.addMouseWheelEvent((float) xOffset, (float) yOffset);

            if (Minecraft.getInstance().gui.screen() == null || !ReplayUI.isMainFrameActive()) {
                return;
            }
        }

        this.forwardScrollToGame(window, xOffset, yOffset);
    }

    /**
     * Called from {@code MixinKeyboardHandler} at the head of {@code KeyboardHandler.keyPress}.
     */
    public void keyCallback(final long window, final int action, final KeyEvent event) {
        if (AsyncFileDialogs.hasDialog()) return;

        final int key = event.key();
        final int mods = event.modifiers();

        if (key <= 0 || key >= SDL_SCANCODE_COUNT) {
            this.forwardKeyToGame(window, action, event);
            return;
        }

        var io = ReplayUI.getIO();

        updateKeyModifiers();

        boolean shiftMod = (mods & SDL_KMOD_SHIFT) != 0 || InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
        boolean ctrlMod = (mods & SDL_KMOD_CTRL) != 0 || InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
        boolean altMod = (mods & SDL_KMOD_ALT) != 0 || InputConstants.isKeyDown(InputConstants.KEY_LALT) || InputConstants.isKeyDown(InputConstants.KEY_RALT);
        boolean superMod = (mods & SDL_KMOD_GUI) != 0 || InputConstants.isKeyDown(InputConstants.KEY_LGUI) || InputConstants.isKeyDown(InputConstants.KEY_RGUI);

        if (this.grabbed != null && action == 0 && this.grabLinkedKey > 0 && key == this.grabLinkedKey) {
            this.ungrab();
        }

        if (!ReplayUI.isActive() || Minecraft.getInstance().gui.screen() != null) {
            if (action == 0 && this.keyOwnedByImGui[key]) {
                final int imguiKey = sdlKeyToImGuiKey(key);
                io.addKeyEvent(imguiKey, false);
                this.keyOwnedByImGui[key] = false;
            }

            this.keyPressedGame[key] = action != 0;

            // Don't allow key presses during export
            if (Flashback.isExporting()) {
                if (action == 0 && this.keyPressedGame[key]) {
                    this.keyPressedGame[key] = false;
                } else {
                    return;
                }
            }

            this.forwardKeyToGame(window, action, event);
            return;
        }

        Keybind editingKeybind = ImGuiHelper.getEditingKeybind();
        if (action == 1 && key != SDL_SCANCODE_ESCAPE && editingKeybind != null) {
            if (editingKeybind.isForceScrollKey()) {
                shiftMod |= key == SDL_SCANCODE_LSHIFT || key == SDL_SCANCODE_RSHIFT;
                ctrlMod |= key == SDL_SCANCODE_LCTRL || key == SDL_SCANCODE_RCTRL;
                altMod |= key == SDL_SCANCODE_LALT || key == SDL_SCANCODE_RALT;
                superMod |= key == SDL_SCANCODE_LGUI || key == SDL_SCANCODE_RGUI;
                editingKeybind.set(Keybind.FAKE_SCROLL_KEY, shiftMod, ctrlMod, altMod, superMod);
                return;
            }

            shiftMod &= key != SDL_SCANCODE_LSHIFT && key != SDL_SCANCODE_RSHIFT;
            ctrlMod &= key != SDL_SCANCODE_LCTRL && key != SDL_SCANCODE_RCTRL;
            altMod &= key != SDL_SCANCODE_LALT && key != SDL_SCANCODE_RALT;
            superMod &= key != SDL_SCANCODE_LGUI && key != SDL_SCANCODE_RGUI;

            if (InputQuirks.REPLACE_CTRL_KEY_WITH_CMD_KEY) {
                boolean temp = ctrlMod;
                ctrlMod = superMod;
                superMod = temp;
            }

            editingKeybind.set(key, shiftMod, ctrlMod, altMod, superMod);
            return;
        }

        if (action == 1 && ImGuiHelper.getWantsSpecialInput()) {
            if (key == SDL_SCANCODE_BACKSPACE && ImGuiHelper.backspaceInput(mods)) {
                return;
            } else if (key == SDL_SCANCODE_SPACE) {
                return;
            }
        }

        boolean passToMinecraft = false;
        boolean passToImGui = false;

        if (action == 0) {
            if (this.keyPressedGame[key]) {
                passToMinecraft = true;
            }
            if (this.keyOwnedByImGui[key]) {
                passToImGui = true;
            }
        } else {
            passToMinecraft = shouldPassToMinecraft(io, key, mods);
            passToImGui = !passToMinecraft;
        }

        if (passToMinecraft) {
            this.forwardKeyToGame(window, action, event);
            this.keyPressedGame[key] = action != 0;
        }

        if (passToImGui) {
            final int imguiKey = sdlKeyToImGuiKey(key);
            // Do NOT call io.setKeyEventNativeData: with a defaulted legacy index ImGui
            // uses native_keycode as the io.KeyMap slot, and 26.3 KeyEvents may report
            // keycode=0. Multiple keys then alias one KeysDown[] mirror slot, which
            // desyncs from KeysData and trips ImGui's legacy-array sanity assert.
            if (action != 0) {
                io.addKeyEvent(imguiKey, true);
                this.keyOwnedByImGui[key] = true;
            } else {
                io.addKeyEvent(imguiKey, false);
                this.keyOwnedByImGui[key] = false;
            }
        }
    }

    private boolean shouldPassToMinecraft(ImGuiIO io, int key, int mods) {
        if (key == SDL_SCANCODE_ESCAPE) {
            return !io.getWantTextInput() && !ReplayUI.hasAnyPopupOpen;
        }

        // If any of our keybinds would be triggered, don't pass to Minecraft
        boolean shiftMod = (mods & SDL_KMOD_SHIFT) != 0;
        boolean ctrlMod = (mods & SDL_KMOD_CTRL) != 0;
        boolean altMod = (mods & SDL_KMOD_ALT) != 0;
        boolean superMod = (mods & SDL_KMOD_GUI) != 0;
        for (Keybind keybind : Keybinds.KEYBINDS) {
            if (keybind.wouldBePressed(key, shiftMod, ctrlMod, altMod, superMod)) {
                return false;
            }
        }

        // Pass all function keys to Minecraft
        if ((key >= SDL_SCANCODE_F1 && key <= SDL_SCANCODE_F12) || (key >= SDL_SCANCODE_F13 && key <= SDL_SCANCODE_F24)) {
            return true;
        }

        // Pass all F3 combinations to Minecraft
        if (InputConstants.isKeyDown(InputConstants.KEY_F3)) {
            return true;
        }

        if (io.getWantTextInput()) {
            return false;
        } else if (this.grabbed == MouseHandledBy.GAME) {
            return true;
        }

        var options = Minecraft.getInstance().options;
        var keyEvent = new KeyEvent(key, SDL_GetKeyFromScancode(key, (short) mods, false), mods);

        // If any Minecraft keybinds would be triggered while focusing the main frame, pass to minecraft
        if (ReplayUI.isMainFrameActive()) {
            for (KeyMapping keyMapping : options.keyMappings) {
                if (keyMapping.matches(keyEvent)) {
                    return true;
                }
            }
        }

        // Special keybinds that take priority even if the main frame isn't focused
        if (options.keyUp.matches(keyEvent) ||
                options.keyLeft.matches(keyEvent) ||
                options.keyDown.matches(keyEvent) ||
                options.keyRight.matches(keyEvent) ||
                options.keyJump.matches(keyEvent) ||
                options.keyChat.matches(keyEvent) ||
                options.keyCommand.matches(keyEvent)) {
            ReplayUI.focusMainWindowCounter = 5;
            return true;
        }

        return false;
    }

    /**
     * Called from {@code MixinMouseHandler} at the head of {@code MouseHandler.onMove}.
     */
    public void cursorPosCallback(final long window, final double xpos, final double ypos, final double xrel, final double yrel) {
        if (AsyncFileDialogs.hasDialog()) return;

        this.rawMouseX = xpos;
        this.rawMouseY = ypos;

        if (!ReplayUI.isActive()) {
            this.forwardMouseMoveToGame(window, xpos, ypos, xrel, yrel);
            return;
        }

        MouseHandledBy handledBy = this.getMouseHandledBy();

        if (this.ignoreMouseMovements > 0) {
            grabbedCurrMouseX = xpos;
            grabbedCurrMouseY = ypos;
            grabbedLastMouseX = xpos;
            grabbedLastMouseY = ypos;
            this.ignoreMouseMovements -= 1;
            return;
        }

        if (handledBy.allowGame()) {
            this.forwardMouseMoveToGame(window, ReplayUI.getNewMouseX(xpos), ReplayUI.getNewMouseY(ypos), xrel, yrel);
        }
        if (handledBy == MouseHandledBy.EDITOR_GRABBED) {
            grabbedCurrMouseX = xpos;
            grabbedCurrMouseY = ypos;
        }
    }

    /**
     * Called from {@code MixinKeyboardHandler} at the head of {@code KeyboardHandler.textInput}.
     */
    public void textInputCallback(final long window, final String text) {
        if (AsyncFileDialogs.hasDialog()) return;

        if (!ReplayUI.isActive()) {
            this.forwardTextInputToGame(window, text);
            return;
        }

        var io = ReplayUI.getIO();
        if (!io.getWantCaptureKeyboard() && !io.getWantTextInput()) {
            this.forwardTextInputToGame(window, text);
        }

        for (int i = 0; i < text.length(); i++) {
            if (!ImGuiHelper.addInputCharacter(text.charAt(i))) {
                io.addInputCharacter(text.charAt(i));
            }
        }
    }

    /**
     * Called from {@code MixinKeyboardHandler} at the head of {@code KeyboardHandler.textEditing}.
     */
    public void textEditingCallback(final long window, final PreeditEvent event) {
        if (AsyncFileDialogs.hasDialog()) return;

        var io = ReplayUI.getIO();
        if (!ReplayUI.isActive() || (!io.getWantCaptureKeyboard() && !io.getWantTextInput())) {
            this.forwardTextEditingToGame(window, event);
        }
    }

    public boolean init(final long windowPtr) {
        this.mainWindowPtr = windowPtr;

        final ImGuiIO io = ReplayUI.getIO();

        io.addBackendFlags(ImGuiBackendFlags.HasMouseCursors | ImGuiBackendFlags.HasSetMousePos);
        io.setBackendPlatformName("imgui_java_impl_sdl");

        KeyboardHandler keyboardHandler = Minecraft.getInstance().keyboardHandler;
        io.setGetClipboardTextFn(new ImStrSupplier() {
            @Override
            public String get() {
                return keyboardHandler.getClipboard();
            }
        });
        io.setSetClipboardTextFn(new ImStrConsumer() {
            @Override
            public void accept(String s) {
                keyboardHandler.setClipboard(s);
            }
        });

        this.mouseCursors[ImGuiMouseCursor.Arrow] = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_DEFAULT);
        this.mouseCursors[ImGuiMouseCursor.TextInput] = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_TEXT);
        this.mouseCursors[ImGuiMouseCursor.ResizeNS] = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_NS_RESIZE);
        this.mouseCursors[ImGuiMouseCursor.ResizeEW] = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_EW_RESIZE);
        this.mouseCursors[ImGuiMouseCursor.Hand] = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_POINTER);
        this.mouseCursors[ImGuiMouseCursor.ResizeAll] = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_MOVE);
        this.mouseCursors[ImGuiMouseCursor.ResizeNESW] = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_NESW_RESIZE);
        this.mouseCursors[ImGuiMouseCursor.ResizeNWSE] = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_NWSE_RESIZE);
        this.mouseCursors[ImGuiMouseCursor.NotAllowed] = SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_NOT_ALLOWED);

        this.updateSizes(io);

        this.updateMonitors();

        final ImGuiViewport mainViewport = ImGui.getMainViewport();
        mainViewport.setPlatformHandle(this.mainWindowPtr);
        if (IS_WINDOWS) {
            int props = SDL_GetWindowProperties(this.mainWindowPtr);
            if (props != 0) {
                mainViewport.setPlatformHandleRaw(SDL_GetPointerProperty(props, SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0));
            }
        }

        this.ready = true;
        return true;
    }

    private void updateSizes(final ImGuiIO io) {
        SDL_GetWindowSize(this.mainWindowPtr, this.winWidth, this.winHeight);
        SDL_GetWindowSizeInPixels(this.mainWindowPtr, this.fbWidth, this.fbHeight);

        io.setDisplaySize((float) this.winWidth.get(0), (float) this.winHeight.get(0));
        if (this.winWidth.get(0) > 0 && this.winHeight.get(0) > 0) {
            final float scaleX = (float) this.fbWidth.get(0) / this.winWidth.get(0);
            final float scaleY = (float) this.fbHeight.get(0) / this.winHeight.get(0);
            io.setDisplayFramebufferScale(scaleX, scaleY);

            float displayScale = SDL_GetWindowDisplayScale(this.mainWindowPtr);
            this.contentScale = Math.max(displayScale / scaleX, displayScale / scaleY);
        }
    }

    /**
     * Updates {@link ImGuiIO} and SDL state. Called once per ImGui frame.
     */
    public void newFrame() {
        final ImGuiIO io = ReplayUI.getIO();

        this.updateSizes(io);

        if (this.monitorRefreshCountdown > 0) {
            this.monitorRefreshCountdown -= 1;
        }
        if (this.wantUpdateMonitors || this.monitorRefreshCountdown == 0) {
            this.updateMonitors();
            this.monitorRefreshCountdown = 60;
        }

        final double currentTime = SDL_GetTicksNS() / 1_000_000_000.0;
        io.setDeltaTime(this.time > 0.0 ? (float) (currentTime - this.time) : 1.0f / 60.0f);
        this.time = currentTime;

        long flags = SDL_GetWindowFlags(this.mainWindowPtr);
        boolean focused = (flags & SDL_WINDOW_INPUT_FOCUS) != 0;
        if (focused != this.lastWindowFocused) {
            this.lastWindowFocused = focused;
            io.addFocusEvent(focused);
        }

        if (AsyncFileDialogs.hasDialog()) {
            if (!this.releasedAllKeysBecauseOfDialog) {
                this.releasedAllKeysBecauseOfDialog = true;

                // Release for game
                for (int key = 0; key < this.keyPressedGame.length; key++) {
                    if (this.keyPressedGame[key]) {
                        this.keyPressedGame[key] = false;
                        int keycode = SDL_GetKeyFromScancode(key, (short) 0, false);
                        this.forwardKeyToGame(this.mainWindowPtr, 0, new KeyEvent(key, keycode, 0));
                    }
                }

                // Release for imgui
                Arrays.fill(this.keyOwnedByImGui, false);
                io.clearInputKeys();
                this.clearModifierKeys(io);
            }

            return;
        } else {
            this.releasedAllKeysBecauseOfDialog = false;
        }

        int mod = SDL_GetModState();
        io.addKeyEvent(ImGuiKey.ModShift, (mod & SDL_KMOD_SHIFT) != 0);
        io.addKeyEvent(ImGuiKey.ModCtrl, (mod & SDL_KMOD_CTRL) != 0);
        io.addKeyEvent(ImGuiKey.ModAlt, (mod & SDL_KMOD_ALT) != 0);
        io.addKeyEvent(ImGuiKey.ModSuper, (mod & SDL_KMOD_GUI) != 0);

        // Keep SDL text input enabled while ImGui wants text, via vanilla's owner-tracked manager
        boolean wantTextInput = io.getWantTextInput();
        if (wantTextInput != this.lastWantTextInput) {
            this.lastWantTextInput = wantTextInput;
            var textInputManager = Minecraft.getInstance().textInputManager();
            if (wantTextInput) {
                textInputManager.startTextInput(this);
            } else {
                textInputManager.stopTextInput(this);
            }
        }

        this.updateMousePosAndButtons();
        this.updateMouseCursor();
        this.updateGamepads();
    }

    public void updateReleaseAllKeys(boolean release) {
        if (release) {
            if (!this.releasedAllKeysBecauseOfDisable) {
                this.releasedAllKeysBecauseOfDisable = true;

                var io = ReplayUI.getIO();

                Arrays.fill(this.keyOwnedByImGui, false);
                io.clearInputKeys();
                this.clearModifierKeys(io);

                SDL_SetCursor(this.mouseCursors[ImGuiMouseCursor.Arrow]);
            }
        } else {
            this.releasedAllKeysBecauseOfDisable = false;
        }
    }

    private void updateMousePosAndButtons() {
        var io = ReplayUI.getIO();

        var mouseHandledBy = this.getMouseHandledBy();
        if (!mouseHandledBy.allowImgui() || AsyncFileDialogs.hasDialog()) {
            for (int i = 0; i < ImGuiMouseButton.COUNT; i++) {
                io.addMouseButtonEvent(i, false);
                this.mouseJustPressed[i] = false;
            }
            return;
        }

        int buttonMask = SDL_GetMouseState(this.mouseXF, this.mouseYF);

        for (int i = 0; i < ImGuiMouseButton.COUNT; i++) {
            // If a mouse press event came, always pass it as "mouse held this frame", so we don't miss click-release events that are shorter than 1 frame.
            // SDL button mask is indexed by SDL button-1 (left/middle/right); ImGui uses left/right/middle order
            int sdlButton = i == ImGuiMouseButton.Right ? 3 : i == ImGuiMouseButton.Middle ? 2 : i + 1;
            io.addMouseButtonEvent(i, this.mouseJustPressed[i] || (buttonMask & (1 << (sdlButton - 1))) != 0);
            this.mouseJustPressed[i] = false;
        }

        io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE);
        io.addMouseViewportEvent(0);

        // Set OS mouse position from Dear ImGui if requested (rarely used, only when ImGuiConfigFlags_NavEnableSetMousePos is enabled by user)
        if (io.getWantSetMousePos() && this.lastWindowFocused) {
            var backup = new ImVec2();
            io.getMousePos(backup);
            SDL_WarpMouseInWindow(this.mainWindowPtr, backup.x, backup.y);
        }

        // Set Dear ImGui mouse position from OS position
        long flags = SDL_GetWindowFlags(this.mainWindowPtr);
        boolean mouseInWindow = (flags & SDL_WINDOW_MOUSE_FOCUS) != 0 || SDL_GetWindowRelativeMouseMode(this.mainWindowPtr);
        if (mouseInWindow) {
            io.addMousePosEvent(this.mouseXF.get(0), this.mouseYF.get(0));
        }
    }

    private void updateMouseCursor() {
        if (AsyncFileDialogs.hasDialog()) return;

        var io = ReplayUI.getIO();
        final boolean noCursorChange = io.hasConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
        final boolean cursorDisabled = SDL_GetWindowRelativeMouseMode(this.mainWindowPtr);

        if (noCursorChange || cursorDisabled) {
            return;
        }

        final int imguiCursor = ImGui.getMouseCursor();

        if (imguiCursor == ImGuiMouseCursor.None || io.getMouseDrawCursor()) {
            // Hide OS mouse cursor if imgui is drawing it or if it wants no cursor
            SDL_HideCursor();
        } else {
            // Show OS mouse cursor
            SDL_SetCursor(this.mouseCursors[imguiCursor] != 0 ? this.mouseCursors[imguiCursor] : this.mouseCursors[ImGuiMouseCursor.Arrow]);
            SDL_ShowCursor();
        }
    }

    @FunctionalInterface
    private interface MapButton {
        void run(int keyNo, int buttonNo);
    }

    @FunctionalInterface
    private interface MapAnalog {
        void run(int keyNo, int axisNo, float v0, float v1);
    }

    @SuppressWarnings("ManualMinMaxCalculation")
    private float saturate(final float v) {
        return v < 0.0f ? 0.0f : v > 1.0f ? 1.0f : v;
    }

    private void updateGamepads() {
        if (AsyncFileDialogs.hasDialog()) return;
        final ImGuiIO io = ReplayUI.getIO();

        if (!io.hasConfigFlags(ImGuiConfigFlags.NavEnableGamepad)) {
            return;
        }

        io.removeBackendFlags(ImGuiBackendFlags.HasGamepad);

        IntBuffer gamepads = SDL_GetGamepads();
        if (gamepads == null || !gamepads.hasRemaining()) {
            this.closeGamepad();
            return;
        }

        int id = gamepads.get(0);
        if (this.gamepad == 0 || this.gamepadId != id) {
            this.closeGamepad();
            this.gamepad = SDL_OpenGamepad(id);
            this.gamepadId = id;
        }
        if (this.gamepad == 0) {
            return;
        }
        if (!SDL_GamepadConnected(this.gamepad)) {
            this.closeGamepad();
            return;
        }

        final long pad = this.gamepad;
        final MapButton mapButton = (keyNo, buttonNo) -> {
            boolean down = SDL_GetGamepadButton(pad, buttonNo);
            io.addKeyEvent(keyNo, down);
        };
        final MapAnalog mapAnalog = (keyNo, axisNo, v0, v1) -> {
            float v = SDL_GetGamepadAxis(pad, axisNo) / 32767.0f;
            v = (v - v0) / (v1 - v0);
            io.addKeyAnalogEvent(keyNo, v > 0.10f, saturate(v));
        };

        io.addBackendFlags(ImGuiBackendFlags.HasGamepad);
        mapButton.run(ImGuiKey.GamepadStart, SDL_GAMEPAD_BUTTON_START);
        mapButton.run(ImGuiKey.GamepadBack, SDL_GAMEPAD_BUTTON_BACK);
        mapButton.run(ImGuiKey.GamepadFaceLeft, SDL_GAMEPAD_BUTTON_WEST);      // Xbox X, PS Square
        mapButton.run(ImGuiKey.GamepadFaceRight, SDL_GAMEPAD_BUTTON_EAST);     // Xbox B, PS Circle
        mapButton.run(ImGuiKey.GamepadFaceUp, SDL_GAMEPAD_BUTTON_NORTH);       // Xbox Y, PS Triangle
        mapButton.run(ImGuiKey.GamepadFaceDown, SDL_GAMEPAD_BUTTON_SOUTH);     // Xbox A, PS Cross
        mapButton.run(ImGuiKey.GamepadDpadLeft, SDL_GAMEPAD_BUTTON_DPAD_LEFT);
        mapButton.run(ImGuiKey.GamepadDpadRight, SDL_GAMEPAD_BUTTON_DPAD_RIGHT);
        mapButton.run(ImGuiKey.GamepadDpadUp, SDL_GAMEPAD_BUTTON_DPAD_UP);
        mapButton.run(ImGuiKey.GamepadDpadDown, SDL_GAMEPAD_BUTTON_DPAD_DOWN);
        mapButton.run(ImGuiKey.GamepadL1, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER);
        mapButton.run(ImGuiKey.GamepadR1, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER);
        mapAnalog.run(ImGuiKey.GamepadL2, SDL_GAMEPAD_AXIS_LEFT_TRIGGER, -0.75f, +1.0f);
        mapAnalog.run(ImGuiKey.GamepadR2, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, -0.75f, +1.0f);
        mapButton.run(ImGuiKey.GamepadL3, SDL_GAMEPAD_BUTTON_LEFT_STICK);
        mapButton.run(ImGuiKey.GamepadR3, SDL_GAMEPAD_BUTTON_RIGHT_STICK);
        mapAnalog.run(ImGuiKey.GamepadLStickLeft, SDL_GAMEPAD_AXIS_LEFTX, -0.25f, -1.0f);
        mapAnalog.run(ImGuiKey.GamepadLStickRight, SDL_GAMEPAD_AXIS_LEFTX, +0.25f, +1.0f);
        mapAnalog.run(ImGuiKey.GamepadLStickUp, SDL_GAMEPAD_AXIS_LEFTY, -0.25f, -1.0f);
        mapAnalog.run(ImGuiKey.GamepadLStickDown, SDL_GAMEPAD_AXIS_LEFTY, +0.25f, +1.0f);
        mapAnalog.run(ImGuiKey.GamepadRStickLeft, SDL_GAMEPAD_AXIS_RIGHTX, -0.25f, -1.0f);
        mapAnalog.run(ImGuiKey.GamepadRStickRight, SDL_GAMEPAD_AXIS_RIGHTX, +0.25f, +1.0f);
        mapAnalog.run(ImGuiKey.GamepadRStickUp, SDL_GAMEPAD_AXIS_RIGHTY, -0.25f, -1.0f);
        mapAnalog.run(ImGuiKey.GamepadRStickDown, SDL_GAMEPAD_AXIS_RIGHTY, +0.25f, +1.0f);
    }

    private void closeGamepad() {
        if (this.gamepad != 0) {
            SDL_CloseGamepad(this.gamepad);
            this.gamepad = 0;
        }
        this.gamepadId = -1;
    }

    protected void updateMonitors() {
        final ImGuiPlatformIO platformIO = ImGui.getPlatformIO();
        this.wantUpdateMonitors = false;

        final IntBuffer displays = SDL_GetDisplays();
        if (displays == null || !displays.hasRemaining()) {
            return;
        }

        platformIO.resizeMonitors(0);

        SDL_Rect bounds = SDL_Rect.malloc();
        SDL_Rect usable = SDL_Rect.malloc();
        try {
            while (displays.hasRemaining()) {
                final int display = displays.get();

                if (!SDL_GetDisplayBounds(display, bounds)) {
                    continue;
                }

                final float mainPosX = bounds.x();
                final float mainPosY = bounds.y();
                final float mainSizeX = bounds.w();
                final float mainSizeY = bounds.h();

                float workPosX = mainPosX;
                float workPosY = mainPosY;
                float workSizeX = mainSizeX;
                float workSizeY = mainSizeY;

                if (SDL_GetDisplayUsableBounds(display, usable) && usable.w() > 0 && usable.h() > 0) {
                    workPosX = usable.x();
                    workPosY = usable.y();
                    workSizeX = usable.w();
                    workSizeY = usable.h();
                }

                float dpiScale = SDL_GetDisplayContentScale(display);

                platformIO.pushMonitors(display, mainPosX, mainPosY, mainSizeX, mainSizeY, workPosX, workPosY, workSizeX, workSizeY, dpiScale);
            }
        } finally {
            bounds.free();
            usable.free();
        }
    }

    /**
     * Kept for API compatibility. Multi-viewport platform windows are not supported by
     * this backend (ImGuiConfigFlags.ViewportsEnable is never set), so this is a no-op.
     */
    public void setViewportWindowsHidden(boolean viewportWindowsHidden) {
    }
}
