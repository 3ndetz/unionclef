package adris.altoclef.util.agent;

import adris.altoclef.AltoClef;

import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.Map;

// AgentControlActionsJSON
//  {"attack": 0,
//   "back": 0,
//   "forward": 1,
//   "jump": 0,
//   "left": 0,
//   "right": 0,
//   "sneak": 0,
//   "sprint": 1,
//   "use": 0,
//   "drop": 0,
//   "inventory": 0,
//   "hotbar.1": 0,
//   "hotbar.2": 0,
//   "hotbar.3": 0,
//   "hotbar.4": 0,
//   "hotbar.5": 0,
//   "hotbar.6": 0,
//   "hotbar.7": 0,
//   "hotbar.8": 0,
//   "hotbar.9": 0,
//   "camera": [
//     0.0,
//     0.0
//   ],
//   "ESC": 0}

public class AgentActionButtons {

    /**
     * Executes actions from a control dictionary (from Python via Py4j)
     * @param mod The AltoClef instance
     * @param controlDict Map with button states (0 or 1)
     */
    public static void executeActions(AltoClef mod, Map<String, Object> controlDict) {
        if (controlDict == null) return;

        if (mod == null) return;

        // Movement controls
        handleButton(mod, controlDict, "forward", Input.MOVE_FORWARD);
        handleButton(mod, controlDict, "back", Input.MOVE_BACK);
        handleButton(mod, controlDict, "left", Input.MOVE_LEFT);
        handleButton(mod, controlDict, "right", Input.MOVE_RIGHT);

        // Action controls
        handleButton(mod, controlDict, "attack", Input.CLICK_LEFT);
        handleButton(mod, controlDict, "use", Input.CLICK_RIGHT);
        handleButton(mod, controlDict, "jump", Input.JUMP);
        handleButton(mod, controlDict, "sneak", Input.SNEAK);
        handleButton(mod, controlDict, "sprint", Input.SPRINT);

        // Hotbar controls
        for (int i = 1; i <= 9; i++) {
            String hotbarKey = "hotbar." + i;
            if (controlDict.containsKey(hotbarKey)) {
                Object value = controlDict.get(hotbarKey);
                if (isPressed(value)) {
                    mod.getPlayer().getInventory().selectedSlot = i - 1;
                }
            }
        }

        // Camera movement
        // DANGEROUS MOMENT
        // should POSITION THE CURSOR CHANGES (delta) instead of absolute positions
        // when in game (no cursor) should change view direction (values = delta)
        // when in invertory / screen (when shown cursor) should move the cursor (values = delta)
        if (controlDict.containsKey("camera")) {
            if (!AgentInputBridge.isAgentInputActive) {
                AgentInputBridge.isAgentInputActive = true;
            }

            Object cameraObj = controlDict.get("camera");
            if (cameraObj instanceof java.util.List) {
                java.util.List<?> cameraList = (java.util.List<?>) cameraObj;
                if (cameraList.size() >= 2) {
                    try {
                        double agentSensivity = 1.0d;
                        // SWAPPED! Since cameraList[0] is pitch (up/down), cameraList[1] is yaw (left/right)
                        // This is in MineStudio VPT config
                        // They also using SENS = camera scaler like 360.0 / 2400.0
                        double yawDelta = toDouble(cameraList.get(1)) * agentSensivity;
                        double pitchDelta = toDouble(cameraList.get(0)) * agentSensivity;
                        //long windowHandle = MinecraftClient.getInstance().getWindow().getHandle();

                        // x and y are screen coordinates (pixels), typically from top-left
                        // GLFW.glfwSetCursorPos(windowHandle, yawDelta, pitchDelta);
                        // works bad, just only in invertory PLACES items on center

                        // AgentInputBridge.addDelta(yawDelta, pitchDelta);
                        MinecraftClient.getInstance().player.changeLookDirection(yawDelta, pitchDelta);
                        //MinecraftClient.getInstance().mouse.onCursorPos(window, yawDelta, pitchDelta);  // window, dx, dy
                        // mod.getInputControls().forceLook((float)yawDelta, (float)pitchDelta);
                    } catch (Exception ignored) {}
                }
            }
        }
        // UNTESTED SECTION
        // looks like not working :(
        MinecraftClient client = MinecraftClient.getInstance();
        // "drop" -> defaults to Q
        handleNativeKey(controlDict, "drop", client.options.dropKey);

        // "inventory" -> defaults to E
        handleNativeKey(controlDict, "inventory", client.options.inventoryKey);

        // "swapHands" -> defaults to F (useful to add)
        handleNativeKey(controlDict, "swapHands", client.options.swapHandsKey);
        // Other controls (BAD APPROACH, KEYS WILL BE SET TO 0 WHEN OTHER KEYS 1!!!)
        // handleButton(mod, controlDict, "drop", Input.CLICK_LEFT); // Can be remapped as needed
        // handleButton(mod, controlDict, "inventory", Input.CLICK_RIGHT); // Can be remapped as needed

        // ESC key - open pause menu
        // if (controlDict.containsKey("ESC") && isPressed(controlDict.get("ESC"))) {
        //     mod.getInputControls().tryPress(Input.);
        //     MinecraftClient.getInstance().setScreen(new net.minecraft.client.gui.screen.PauseScreen(false));
        // }
    }

    /**
     * Handles a single button state
     */
    private static void handleButton(AltoClef mod, Map<String, Object> controlDict, String key, Input input) {
        if (!controlDict.containsKey(key)) return;

        Object value = controlDict.get(key);
        if (isPressed(value)) {
            mod.getInputControls().hold(input);
        } else {
            mod.getInputControls().release(input);
        }
    }

    public static void handleNativeKeyDropTest(AltoClef mod) {
        // Drops the currently selected item
        MinecraftClient client = MinecraftClient.getInstance();
        KeyBinding keyBinding = client.options.dropKey;
        boolean pressed = true;
        keyBinding.setPressed(pressed);
    }

    /**
     * Handles Native Minecraft KeyBindings.
     * This bypasses Baritone and speaks directly to the game options.
     */
    public static void handleNativeKey(Map<String, Object> controlDict, String key, KeyBinding keyBinding) {
        if (!controlDict.containsKey(key)) return;

        boolean pressed = isPressed(controlDict.get(key));

        // setPressed updates the state explicitly.
        // This allows holding (e.g., holding Q to drop a stack) or tapping.
        keyBinding.setPressed(pressed);

        // OPTIONAL: If the key isn't triggering on a single frame "1" signal,
        // you might need to artificially increment the press times for one-shot actions,
        // but setPressed(true) usually works for standard input emulation.
        // if (pressed) { KeyBinding.onKeyPressed(keyBinding.getDefaultKey()); }
    }

    /**
     * Checks if a button is pressed (value is 1 or true)
     */
    private static boolean isPressed(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) return "1".equals(value) || "true".equalsIgnoreCase((String) value);
        return false;
    }

    /**
     * Safely converts an object to double
     */
    private static double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try { return Double.parseDouble((String) value); } catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }
}
