package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

public class PlayerExtraController {

    private final AltoClef mod;
    private BlockPos blockBreakPos;
    private double blockBreakProgress;
    public static boolean IsPvpRotating;
    private static boolean _succesfulHit = false;

    public PlayerExtraController(AltoClef mod) {
        this.mod = mod;

        EventBus.subscribe(BlockBreakingEvent.class, evt -> onBlockBreak(evt.blockPos, evt.progress));
        EventBus.subscribe(BlockBreakingCancelEvent.class, evt -> onBlockStopBreaking());
    }

    private void onBlockBreak(BlockPos pos, double progress) {
        blockBreakPos = pos;
        blockBreakProgress = progress;
    }

    private void onBlockStopBreaking() {
        blockBreakPos = null;
        blockBreakProgress = 0;
    }

    public BlockPos getBreakingBlockPos() {
        return blockBreakPos;
    }

    public boolean isBreakingBlock() {
        return blockBreakPos != null;
    }

    public double getBreakingBlockProgress() {
        return blockBreakProgress;
    }

    public boolean inRange(Entity entity) {
        return LookHelper.canHitEntity(mod, entity); // checks both LOS and reach range (matching autoclef)
    }

    /**
     * Attack entity via simulated left-click (anti-cheat safe).
     * Only attacks if the game's crosshair is actually targeting an attackable entity.
     * This naturally prevents through-wall attacks without explicit LOS checks.
     */
    public boolean attack(Entity entity, boolean doRotates) {
        _succesfulHit = false;
        LookHelper.smoothLook(mod, entity);
        boolean attackable;
        if (MinecraftClient.getInstance().targetedEntity != null) {
            attackable = MinecraftClient.getInstance().targetedEntity.isAttackable();
        } else {
            attackable = false;
        }
        if (attackable) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getInputControls().tryPress(Input.CLICK_LEFT);
            mod.getDamageTracker().onClientMeleeAttack(entity);
            _succesfulHit = true;
        }
        return _succesfulHit;
    }

    public boolean attack(Entity entity) {
        return this.attack(entity, false);
    }
}
