package adris.altoclef.chains;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import kaptainwutax.tungsten.path.movements.Rotation;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

public class PlayerInteractionFixChain extends TaskChain {

    /** Cursor stacks kept instead of being dropped after canThrowAwayStack said no. */
    public static volatile int fixKeptCursor;

    private final TimerGame stackHeldTimeout = new TimerGame(1);
    private final TimerGame generalDuctTapeSwapTimeout = new TimerGame(30);
    private final TimerGame shiftDepressTimeout = new TimerGame(10);
    private final TimerGame betterToolTimer = new TimerGame(0);
    private final TimerGame mouseMovingButScreenOpenTimeout = new TimerGame(1);
    private ItemStack lastHandStack = null;

    private Screen lastScreen;
    private Rotation lastLookRotation;

    public PlayerInteractionFixChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onStop() {

    }

    @Override
    public void onInterrupt(TaskChain other) {

    }

    @Override
    protected void onTick() {
    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        AltoClef mod = AltoClef.getInstance();

        if (mod.getUserTaskChain().isActive() && betterToolTimer.elapsed()) {
            // Equip the right tool for the job if we're not using one.
            betterToolTimer.reset();
            if (mod.getControllerExtras().isBreakingBlock()) {
                BlockState state = mod.getWorld().getBlockState(mod.getControllerExtras().getBreakingBlockPos());
                Optional<Slot> bestToolSlot = StorageHelper.getBestToolSlot(mod, state);
                Slot currentEquipped = PlayerSlot.getEquipSlot();

                // if baritone is running, only accept tools OUTSIDE OF HOTBAR!
                // Baritone will take care of tools inside the hotbar.
                if (bestToolSlot.isPresent() && !bestToolSlot.get().equals(currentEquipped)) {
                    // ONLY equip if the item class is STRICTLY different (otherwise we swap around a lot)
                    if (StorageHelper.getItemStackInSlot(currentEquipped).getItem() != StorageHelper.getItemStackInSlot(bestToolSlot.get()).getItem()) {
                        boolean isAllowedToManage = (!Nav.isPathing() ||
                                bestToolSlot.get().getInventorySlot() >= 9) && !mod.getFoodChain().isTryingToEat();
                        if (isAllowedToManage) {
                            Debug.logMessage("Found better tool in inventory, equipping.");
                            ItemStack bestToolItemStack = StorageHelper.getItemStackInSlot(bestToolSlot.get());
                            Item bestToolItem = bestToolItemStack.getItem();
                            mod.getSlotHandler().forceEquipItem(bestToolItem);
                        }
                    }
                }
            }
        }

        // Unpress shift (it gets stuck for some reason???)
        if (mod.getInputControls().isHeldDown(Input.SNEAK)) {
            if (shiftDepressTimeout.elapsed()) {
                mod.getInputControls().release(Input.SNEAK);
            }
        } else {
            shiftDepressTimeout.reset();
        }

        // Refresh inventory
        if (generalDuctTapeSwapTimeout.elapsed()) {
            if (!mod.getControllerExtras().isBreakingBlock()) {
                Debug.logMessage("Refreshed inventory...");
                mod.getSlotHandler().refreshInventory();
                generalDuctTapeSwapTimeout.reset();
                return Float.NEGATIVE_INFINITY;
            }
        }

        ItemStack currentStack = StorageHelper.getItemStackInCursorSlot();

        if (currentStack != null && !currentStack.isEmpty()) {
            //noinspection PointlessNullCheck
            if (lastHandStack == null || !ItemStack.areEqual(currentStack, lastHandStack)) {
                // We're holding a new item in our stack!
                stackHeldTimeout.reset();
                lastHandStack = currentStack.copy();
            }
        } else {
            stackHeldTimeout.reset();
            lastHandStack = null;
        }

        // If we have something in our hand for a period of time...
        if (lastHandStack != null && stackHeldTimeout.elapsed()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(lastHandStack, false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                return Float.NEGATIVE_INFINITY;
            }
            if (ItemHelper.canThrowAwayStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return Float.NEGATIVE_INFINITY;
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            if (garbage.isPresent()) {
                mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                return Float.NEGATIVE_INFINITY;
            }
            // ⛔ THIS ASKS "MAY I THROW THIS AWAY", IS TOLD NO, AND THROWS IT AWAY.
            //
            // The branch above only fires when canThrowAwayStack said YES. Reaching here means it
            // said NO and there is no garbage slot either -- and this line then drops the stack on
            // the ground regardless. Slot.UNDEFINED is index -999, which is vanilla's click
            // outside the window, i.e. a drop.
            //
            // Measured: shThrown=70 in a twelve-minute run, and on a twenty-minute one the item
            // lost this way was the bot's WOODEN PICKAXE. It then spent the rest of that run
            // chasing it into a cave -- lock=wooden_pickaxe:41.6>41.6, h34.0, dy-24.0 -- and
            // finished with dirt and a mushroom, no wood, ladder zero, on a run with ZERO deaths
            // and 39 of 53 targets reached. The movement work landed and this threw the run away.
            //
            // Keeping it costs a tick: the cursor still holds the stack and the first branch above
            // places it as soon as any slot frees. Throwing it costs the item, and sometimes the
            // run.
            if (kaptainwutax.tungsten.TungstenConfig.get().neverThrowWhatCannotBeThrown) {
                fixKeptCursor++;
                return Float.NEGATIVE_INFINITY;
            }
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            return Float.NEGATIVE_INFINITY;
        }

        if (shouldCloseOpenScreen()) {
            //Debug.logMessage("Closed screen since we changed our look.");
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (!cursorStack.isEmpty()) {
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                    return Float.NEGATIVE_INFINITY;
                }
                if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                    return Float.NEGATIVE_INFINITY;
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                // Try throwing away cursor slot if it's garbage
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                    return Float.NEGATIVE_INFINITY;
                }
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            } else {
                StorageHelper.closeScreen();
            }
            return Float.NEGATIVE_INFINITY;
        }

        return Float.NEGATIVE_INFINITY;
    }

    private boolean shouldCloseOpenScreen() {
        if (!AltoClef.getInstance().getModSettings().shouldCloseScreenWhenLookingOrMining())
            return false;

        // Only check look if we've had the same screen open for a while
        Screen openScreen = MinecraftClient.getInstance().currentScreen;
        if (openScreen != lastScreen) {
            mouseMovingButScreenOpenTimeout.reset();
        }
        // We're in the player screen/a screen we DON'T want to cancel out of
        if (openScreen == null || openScreen instanceof ChatScreen || openScreen instanceof GameMenuScreen || openScreen instanceof DeathScreen) {
            mouseMovingButScreenOpenTimeout.reset();
            return false;
        }
        // Check for rotation change
        Rotation look = LookHelper.getLookRotation();
        if (lastLookRotation != null && mouseMovingButScreenOpenTimeout.elapsed()) {
            Rotation delta = look.subtract(lastLookRotation);
            if (Math.abs(delta.getYaw()) > 0.1f || Math.abs(delta.getPitch()) > 0.1f) {
                lastLookRotation = look;
                return true;
            }
            // do NOT update our last look rotation, just because we want to measure long term rotation.
        } else {
            lastLookRotation = look;
        }
        lastScreen = openScreen;
        return false;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Hand Stack Fix Chain";
    }
}
