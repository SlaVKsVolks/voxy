package me.cortex.voxy.client;

import me.cortex.voxy.client.core.debug.RenderStateDiagnostics;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public final class VoxyDiagnosticsHotkey {
    private static boolean initialized = false;
    private static boolean captureKeyWasDown = false;

    private VoxyDiagnosticsHotkey() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        NeoForge.EVENT_BUS.addListener(VoxyDiagnosticsHotkey::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        long window = client.getWindow().getWindow();
        boolean captureKeyDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
        if (captureKeyDown && !captureKeyWasDown) {
            RenderStateDiagnostics.captureNow("hotkey");
            if (client.gui != null) {
                client.gui.getChat().addMessage(Component.translatable("voxy.diagnostics.capture.done"));
            }
        }
        captureKeyWasDown = captureKeyDown;
    }
}
