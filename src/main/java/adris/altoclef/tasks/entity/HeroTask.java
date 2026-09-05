package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.SlimeEntity;

import java.util.Optional;

public class HeroTask extends Task {
    @Override
    protected void onStart() {

    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (mod.getFoodChain().needsToEat()) {
            setDebugState("Eat first.");
            return null;
        }
        Optional<Entity> experienceOrb = mod.getEntityTracker().getClosestEntity(ExperienceOrbEntity.class);
        if (experienceOrb.isPresent()) {
            setDebugState("Getting experience.");
            return new GetToEntityTask(experienceOrb.get());
        }
        // ⛔ FIXED 2026-09-05: this used to loop world.getEntities() (NOT distance-sorted) and act
        // on whichever hostile/slime happened to appear FIRST in that arbitrary iteration order,
        // then ask the tracker for the closest entity of ONLY that one type -- so with, say, a
        // zombie and a much closer skeleton both present, it could chase the zombie and never
        // consider the skeleton at all, because the loop already returned on the zombie. The
        // tracker's own getClosestEntity(Class...) takes multiple types together and returns the
        // genuinely closest match across all of them -- the API this should have called directly.
        Optional<Entity> closestHostile = mod.getEntityTracker().getClosestEntity(HostileEntity.class, SlimeEntity.class);
        if (closestHostile.isPresent()) {
            setDebugState("Killing hostiles or picking hostile drops.");
            return new KillAndLootTask(closestHostile.get().getClass(), new ItemTarget(ItemHelper.HOSTILE_MOB_DROPS));
        }
        if (mod.getEntityTracker().itemDropped(ItemHelper.HOSTILE_MOB_DROPS)) {
            setDebugState("Picking hostile drops.");
            return new PickupDroppedItemTask(new ItemTarget(ItemHelper.HOSTILE_MOB_DROPS), true);
        }
        setDebugState("Searching for hostile mobs.");
        return new TimeoutWanderTask();
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof HeroTask;
    }

    @Override
    protected String toDebugString() {
        return "Killing all hostile mobs.";
    }
}
