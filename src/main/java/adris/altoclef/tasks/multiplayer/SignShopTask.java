package adris.altoclef.tasks.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.ChestSlot;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.utils.input.Input;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.BlockState;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;

/**
 * Interacts with a SignShop sign (common multiplayer shop plugin).
 * <p>
 * Sign format:
 * <pre>
 *   Line 1: [Free] / [Buy] / [Sell]  (with brackets)
 *   Line 2: Quantity (int 1-64)       (if [Free] — this is item id instead, line 3 shifts to 2)
 *   Line 3: Item id, e.g. "349:3"    (absent if [Free])
 *   Line 4: Price                     (absent if [Free])
 * </pre>
 * <p>
 * Currently supports [Free] signs only: right-clicks the sign, waits for a chest
 * to open, takes the item from the top-left slot (slot 0), then closes.
 */
public class SignShopTask extends Task {

    private final BlockPos signPos;

    /** State machine for the shop interaction. */
    private enum State {
        CLICK_SIGN,
        WAIT_CHEST,
        TAKE_ITEM,
        CLOSE,
        DONE
    }

    private State state = State.CLICK_SIGN;
    private int waitTicks = 0;
    private int retryDelay = 0;
    private int retryCount = 0;
    private int approachTicks = 0;
    private int lookTicks = 0;
    private static final int MAX_WAIT_TICKS = 40;       // 2s for chest to open
    private static final int MAX_APPROACH_TICKS = 200;  // 10s to reach the sign
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_TICKS = 10;
    private static final int LOOK_JIGGLE_TICKS = 10;    // 0.5s — start jiggling + clicking
    private static final int LOOK_GIVEUP_TICKS = 30;    // 1.5s — force click, move to WAIT_CHEST

    public SignShopTask(BlockPos signPos) {
        this.signPos = signPos;
    }

    @Override
    protected void onStart() {
        state = State.CLICK_SIGN;
        waitTicks = 0;
        retryDelay = 0;
        retryCount = 0;
        approachTicks = 0;
        lookTicks = 0;
        Debug.logMessage("[SignShop] Starting at " + signPos.toShortString());
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        switch (state) {
            case CLICK_SIGN -> {
                if (StorageHelper.isChestOpen()) {
                    state = State.TAKE_ITEM;
                    return null;
                }
                if (retryDelay > 0) {
                    retryDelay--;
                    setDebugState("Waiting before retry: " + retryDelay);
                    return null;
                }
                Direction facing = getSignFacing(mod);
                // We do NOT use InteractWithBlockTask — its rightClick() calls closeScreen() on
                // any open non-player-inventory screen, which closes the chest the sign just opened.

                // Try reach with specific facing first, then fallback to any face
                boolean inReach = LookHelper.getReach(signPos, facing).isPresent()
                        || LookHelper.getReach(signPos).isPresent();

                if (!inReach) {
                    approachTicks++;
                    if (approachTicks > MAX_APPROACH_TICKS) {
                        approachTicks = 0;
                        retryCount++;
                        if (retryCount >= MAX_RETRIES) {
                            Debug.logWarning("[SignShop] Can't reach " + signPos.toShortString() + ", blacklisting.");
                            mod.getBlockScanner().requestBlockUnreachable(signPos);
                            state = State.DONE;
                            return null;
                        }
                        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                        retryDelay = RETRY_DELAY_TICKS;
                        return null;
                    }
                    ICustomGoalProcess proc = mod.getClientBaritone().getCustomGoalProcess();
                    if (!proc.isActive()) {
                        proc.setGoalAndPath(new GoalNear(signPos, 2));
                    }
                    setDebugState("Walking to sign (" + approachTicks + "/" + MAX_APPROACH_TICKS + ")");
                    return null;
                }
                // In reach — stop pathing, look at the sign and click
                approachTicks = 0;
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                lookTicks++;

                boolean looking = LookHelper.isLookingAt(mod, signPos);

                if (!looking && lookTicks <= LOOK_GIVEUP_TICKS) {
                    if (lookTicks > LOOK_JIGGLE_TICKS) {
                        // Jiggle around the sign block + spam RMB every 3 ticks
                        Vec3d center = signPos.toCenterPos();
                        double jx = (Math.random() - 0.5) * 0.6;
                        double jy = (Math.random() - 0.5) * 0.6;
                        double jz = (Math.random() - 0.5) * 0.6;
                        LookHelper.lookAt(mod, center.add(jx, jy, jz));
                        if (lookTicks % 3 == 0) {
                            mod.getSlotHandler().forceDeequipRightClickableItem();
                            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                        }
                        setDebugState("Jiggling at sign (" + lookTicks + "/" + LOOK_GIVEUP_TICKS + ")");
                    } else if (facing != null && LookHelper.getReach(signPos, facing).isPresent()) {
                        LookHelper.lookAt(mod, signPos, facing);
                        setDebugState("Looking at sign (" + lookTicks + "/" + LOOK_GIVEUP_TICKS + ")");
                    } else {
                        LookHelper.lookAt(mod, signPos);
                        setDebugState("Looking at sign (" + lookTicks + "/" + LOOK_GIVEUP_TICKS + ")");
                    }
                    return null;
                }

                // Either looking at sign or gave up waiting — click and move on
                setDebugState("Clicking sign");
                mod.getSlotHandler().forceDeequipRightClickableItem();
                mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                state = State.WAIT_CHEST;
                waitTicks = 0;
                lookTicks = 0;
                return null;
            }
            case WAIT_CHEST -> {
                setDebugState("Waiting for chest (" + waitTicks + "/" + MAX_WAIT_TICKS + ")");
                if (StorageHelper.isChestOpen()) {
                    state = State.TAKE_ITEM;
                    Debug.logMessage("[SignShop] Chest opened, taking item...");
                    return null;
                }
                waitTicks++;
                if (waitTicks > MAX_WAIT_TICKS) {
                    retryCount++;
                    waitTicks = 0;
                    if (retryCount >= MAX_RETRIES) {
                        Debug.logWarning("[SignShop] Chest never opened after " + MAX_RETRIES + " clicks, blacklisting " + signPos.toShortString());
                        mod.getBlockScanner().requestBlockUnreachable(signPos);
                        state = State.DONE;
                        return null;
                    }
                    Debug.logWarning("[SignShop] Chest didn't open, retry " + retryCount + "/" + MAX_RETRIES);
                    state = State.CLICK_SIGN;
                    approachTicks = 0;
                    lookTicks = 0;
                    retryDelay = RETRY_DELAY_TICKS;
                }
                return null;
            }
            case TAKE_ITEM -> {
                setDebugState("Taking item from slot 0");
                // Slot 0 = top-left of the chest
                ChestSlot slot = new ChestSlot(0, false);
                // QUICK_MOVE (shift-click) moves it straight to player inventory
                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.QUICK_MOVE);
                state = State.CLOSE;
                return null;
            }
            case CLOSE -> {
                setDebugState("Closing chest");
                StorageHelper.closeScreen();
                state = State.DONE;
                Debug.logMessage("[SignShop] Done.");
                return null;
            }
            case DONE -> {
                // finished
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean isFinished() {
        return state == State.DONE;
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Close chest if still open
        if (StorageHelper.isChestOpen()) {
            StorageHelper.closeScreen();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof SignShopTask task) {
            return task.signPos.equals(signPos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "SignShop at " + signPos.toShortString();
    }

    /**
     * Returns the direction the sign's face is pointing.
     * Wall signs use HORIZONTAL_FACING; floor signs convert ROTATION (0-15) to a cardinal direction.
     * Returns null if the block has neither property (e.g. unknown sign type).
     */
    private Direction getSignFacing(AltoClef mod) {
        BlockState state = mod.getWorld().getBlockState(signPos);
        if (state.contains(Properties.HORIZONTAL_FACING)) {
            return state.get(Properties.HORIZONTAL_FACING);
        }
        if (state.contains(Properties.ROTATION)) {
            int rot = state.get(Properties.ROTATION);
            // 0=south, 4=west, 8=north, 12=east (each unit = 22.5°); round to nearest quarter
            return switch (((rot + 2) / 4) % 4) {
                case 1 -> Direction.WEST;
                case 2 -> Direction.NORTH;
                case 3 -> Direction.EAST;
                default -> Direction.SOUTH;
            };
        }
        return null;
    }

    // ── static helpers for reading sign text ────────────────────────────────

    /**
     * Reads the front text lines of a sign at the given position.
     * Returns null if no sign found. Array is always length 4.
     */
    public static String[] readSignLines(World world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sign)) return null;
        SignText text = sign.getFrontText();
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = text.getMessage(i, false).getString().trim();
        }
        return lines;
    }

    /** Returns true if the sign at pos is a [Free] shop sign. */
    public static boolean isFreeShopSign(World world, BlockPos pos) {
        String[] lines = readSignLines(world, pos);
        if (lines == null) return false;
        return lines[0].equalsIgnoreCase("[Free]");
    }

    /** Returns true if the sign at pos is a [Buy] shop sign. */
    public static boolean isBuyShopSign(World world, BlockPos pos) {
        String[] lines = readSignLines(world, pos);
        if (lines == null) return false;
        return lines[0].equalsIgnoreCase("[Buy]");
    }

    /** Returns true if the sign at pos is a [Sell] shop sign. */
    public static boolean isSellShopSign(World world, BlockPos pos) {
        String[] lines = readSignLines(world, pos);
        if (lines == null) return false;
        return lines[0].equalsIgnoreCase("[Sell]");
    }

    /**
     * Returns the item id from a [Free] sign.
     * For [Free] signs, item id is on line 2 (index 1).
     * Returns null if not a [Free] sign.
     */
    public static String getFreeSignItemId(World world, BlockPos pos) {
        String[] lines = readSignLines(world, pos);
        if (lines == null) return null;
        if (!lines[0].equalsIgnoreCase("[Free]")) return null;
        return lines[1].trim();
    }

    /**
     * Finds the nearest [Free] sign with the given item id.
     * Scans all loaded sign block entities.
     */
    public static Optional<BlockPos> findNearestFreeSign(AltoClef mod, String itemId) {
        World world = mod.getWorld();
        // Use block scanner to find signs nearby
        return mod.getBlockScanner().getNearestBlock(
                mod.getPlayer().getPos(),
                pos -> {
                    String id = getFreeSignItemId(world, pos);
                    return id != null && id.equals(itemId);
                },
                ItemHelper.WOOD_SIGNS_ALL
        );
    }
}
