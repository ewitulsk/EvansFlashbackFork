package com.moulberry.flashback;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.BufferUtils;
import org.lwjgl.sdl.SDLVideo;

import java.nio.IntBuffer;

public class WindowSizeTracker {

    /**
     * Some mods (e.g. qdaa) like to manipulate the framebuffer width
     * This can cause rendering issues since we don't know the real width
     * This helper will cache and calculate the real framebuffer width to avoid this issue
     */

    private static int lastFramebufferWidth;
    private static int lastFramebufferHeight;
    private static int realFramebufferWidth;
    private static int realFramebufferHeight;

    public static int getWidth(Window window) {
        if (lastFramebufferWidth != window.framebufferWidth) {
            recalculate(window);
        }

        return realFramebufferWidth;
    }


    public static int getHeight(Window window) {
        if (lastFramebufferHeight != window.framebufferHeight) {
            recalculate(window);
        }

        return realFramebufferHeight;
    }

    private static void recalculate(Window window) {
        // Calculate real framebuffer width/height
        IntBuffer width = BufferUtils.createIntBuffer(1);
        IntBuffer height = BufferUtils.createIntBuffer(1);
        SDLVideo.SDL_GetWindowSizeInPixels(window.handle(), width, height);
        realFramebufferWidth = width.get(0) > 0 ? width.get(0) : 1;
        realFramebufferHeight = height.get(0) > 0 ? height.get(0) : 1;

        // Update cached values
        lastFramebufferWidth = window.framebufferWidth;
        lastFramebufferHeight = window.framebufferHeight;
    }

}
