package com.moulberry.flashback.exporting.taskbar;

import com.moulberry.flashback.Flashback;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.PointerByReference;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

import static com.moulberry.flashback.editor.ui.CustomImGuiImplSdl.IS_WINDOWS;

public class TaskbarHost {
    public static ITaskbar createTaskbar() {
        if (IS_WINDOWS) {
            try {
                return createWindowsInterface();
            } catch (Exception e) {
                Flashback.LOGGER.error("Unable to create windows taskbar interface", e);
                return new NoopTaskbar();
            }
        } else {
            return new NoopTaskbar();
        }
    }

    private static WindowsTaskbar createWindowsInterface() {
        var itaskbar3res = new PointerByReference();

        if (W32Errors.FAILED(Ole32.INSTANCE.CoCreateInstance(new Guid.GUID("56FDF344-FD6D-11d0-958A-006097C9A090"),
                null,
                WTypes.CLSCTX_SERVER,
                new Guid.GUID("EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF"),
                itaskbar3res))) {
            throw new IllegalStateException("Failed to create ITaskbar3");
        }


        long sdlWindow = Minecraft.getInstance().getWindow().handle();
        int props = SDLVideo.SDL_GetWindowProperties(sdlWindow);
        long hwndPointer = SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
        var hwnd = new WinDef.HWND(new Pointer(hwndPointer));
        return new WindowsTaskbar(itaskbar3res.getValue(), hwnd);
    }
}
