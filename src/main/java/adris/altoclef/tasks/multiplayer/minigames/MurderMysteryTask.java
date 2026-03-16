package adris.altoclef.tasks.multiplayer.minigames;

import adris.altoclef.AltoClef;
import adris.altoclef.butler.ButlerConfig;
import adris.altoclef.tasks.entity.KillPlayerTask;
import adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.threats.DamageTrackerStrategy;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

import static adris.altoclef.util.helpers.ItemHelper.clickCustomItem;

public class MurderMysteryTask extends Task {

    // Radius at which a dangerous enemy is detected / loot near enemies is blacklisted
    private static final double DANGER_RADIUS = 20.0;

    private Vec3d _closestPlayerLastPos;
    private Vec3d _closestPlayerLastObservePos;
    private double _closestDistance;
    private boolean _targetIsNear = false;
    private boolean _forceWait = false;
    private Task _shootArrowTask;
    private Task _pickupTask;
    public String _killerName;
    public HashMap<String, MurderRole> _roles = new HashMap<>();
    public int _chill_tactics = -1;
    public boolean _chill_tactics_changed = false;
    public boolean _killed = false;
    private MurderRole _role;
    private Task _runAwayTask;
    private final TimerGame _runAwayExtraTime = new TimerGame(5);
    private final boolean _change_chain_priority = false;

    private List<Item> lootableItems(AltoClef mod) {
        List<Item> lootable = new ArrayList<>();
        lootable.add(Items.GOLDEN_APPLE);
        lootable.add(Items.ENCHANTED_GOLDEN_APPLE);
        lootable.add(Items.GOLDEN_CARROT);
        lootable.add(Items.GOLD_INGOT);
        lootable.add(Items.BOW);
        lootable.add(Items.ARROW);
        lootable.add(Items.GOLD_INGOT);
        lootable.add(Items.DIAMOND);
        lootable.add(Items.IRON_INGOT);
        lootable.add(Items.BOW);
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            lootable.add(Items.WATER_BUCKET);
        }
        return lootable;
    }

    public enum MurderRole {
        KILLER,
        DETECTIVE,
        INNOCENT,
        UNDECIDED
    }

    public MurderMysteryTask(int role) {
        switch (role) {
            case 0:
                _role = MurderRole.INNOCENT;
                break;
            case 1:
                _role = MurderRole.DETECTIVE;
                break;
            case 2:
                _role = MurderRole.KILLER;
                break;
            case -1:
            default:
                _role = MurderRole.UNDECIDED;
                break;
        }
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockBreaking(this::avoidBlockBreak);
        mod.getBehaviour().avoidBlockPlacing(this::avoidBlockBreak);
        mod.getBehaviour().setDamageTrackerStrategy(DamageTrackerStrategy.MurderMystery);
        if (_change_chain_priority)
            mod.getBehaviour().setUserTaskChainPriority(80);
    }

    private boolean avoidBlockBreak(BlockPos pos) {
        return true;
    }

    public void resetGameInfo() {
        _role = MurderRole.UNDECIDED;
        _roles.clear();
        _killerName = null;
        _killed = false;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (mod.getPlayer() != null && !isValidPlayerMM(mod.getPlayer())) {
            if (!_killed) {
                _killed = true;
                mod.getDamageTracker().onPlayerRemove(mod.getPlayer());
            }
        }

        if (ButlerConfig.getInstance().autoJoin) {
            if (clickCustomItem(mod, "новая игра", "начать игру", "быстро играть (пкм)")) {
                resetGameInfo();
            }
        }

        boolean injured = isInjured(mod.getPlayer());

        Optional<Entity> closestDanger;
        if (!isReadyToPunk(mod)) {
            closestDanger = mod.getEntityTracker().getClosestEntity(
                    mod.getPlayer().getPos(),
                    toPunk -> shouldAvoid(mod, (PlayerEntity) toPunk),
                    PlayerEntity.class);
        } else {
            closestDanger = Optional.empty();
        }

        Optional<Entity> closest = mod.getEntityTracker().getClosestEntity(
                mod.getPlayer().getPos(),
                toPunk -> isEnemy(mod, (PlayerEntity) toPunk),
                PlayerEntity.class);

        if (closest.isPresent()) {
            _closestPlayerLastPos = closest.get().getPos();
            _closestPlayerLastObservePos = mod.getPlayer().getPos();
            _closestDistance = _closestPlayerLastPos.distanceTo(_closestPlayerLastObservePos);
            if (_closestDistance <= 8 && mod.getEntityTracker().isEntityReachable(closest.get()))
                _targetIsNear = true;
        }

        if (_forceWait && !_targetIsNear) {
            return null;
        }

        if (shouldForce(_shootArrowTask)) {
            return _shootArrowTask;
        }
        if (shouldForce(_runAwayTask)) {
            if (_runAwayExtraTime.elapsed()) {
                _runAwayTask = null;
            } else {
                return _runAwayTask;
            }
        }

        if (hasKillerWeapon(mod)) {
            _role = MurderRole.KILLER;
        }
        if (!_role.equals(MurderRole.KILLER)) {
            if (hasDetectiveWeapon(mod.getPlayer()))
                _role = MurderRole.DETECTIVE;
        }

        Optional<Entity> closestKiller = mod.getEntityTracker().getClosestEntity(
                mod.getPlayer().getPos(),
                toPunk -> hasKillerWeapon((PlayerEntity) toPunk),
                PlayerEntity.class);
        Optional<Entity> closestDetective = mod.getEntityTracker().getClosestEntity(
                mod.getPlayer().getPos(),
                toPunk -> hasDetectiveWeapon((PlayerEntity) toPunk),
                PlayerEntity.class);

        if (closestKiller.isPresent()) {
            _killerName = closestKiller.get().getName().getString();
            _roles.put(_killerName, MurderRole.KILLER);
        }
        if (closestDetective.isPresent()) {
            String name = closestDetective.get().getName().getString();
            if (!Objects.equals(_roles.get(name), MurderRole.KILLER)) {
                _roles.put(name, MurderRole.DETECTIVE);
            }
        }

        if (closestDanger.isPresent() && !injured) {
            Entity danger = closestDanger.get();
            if (mod.getPlayer().distanceTo(danger) < DANGER_RADIUS) {
                setDebugState("RUNNING FROM DANGER");
                _runAwayExtraTime.reset();
                if (_change_chain_priority)
                    mod.getBehaviour().setUserTaskChainPriority(80);
                _runAwayTask = new RunAwayFromPositionTask(40, danger.getBlockPos());
                return _runAwayTask;
            }
        }

        if (closest.isPresent() && isReadyToPunk(mod)) {
            PlayerEntity entity = (PlayerEntity) closest.get();
            float dist = mod.getPlayer().distanceTo(entity);
            boolean tooClose = dist < 6f;

            if (_role.equals(MurderRole.KILLER) && !injured) {
                if (tooClose) {
                    mod.getSlotHandler().forceEquipItem(Items.SHEARS, Items.IRON_SWORD);
                } else {
                    mod.getSlotHandler().forceDeequip(stack -> stack.getItem() instanceof ShearsItem || stack.getItem() instanceof SwordItem);
                }
                if (_change_chain_priority) mod.getBehaviour().setUserTaskChainPriority(80);
                return new KillPlayerTask(entity.getName().getString());
            }

            if (LookHelper.cleanLineOfSight(entity.getPos(), dist)) {
                String name = entity.getName().getString();
                if (mod.getItemStorage().getItemCount(Items.ENDER_PEARL) > 2) {
                    return new ThrowEnderPearlSimpleProjectileTask(entity.getBlockPos().add(0, -1, 0));
                } else if (Objects.equals(_roles.get(name), MurderRole.KILLER)) {
                    if (useBow(mod, entity)) {
                        if (_change_chain_priority) mod.getBehaviour().setUserTaskChainPriority(80);
                        _shootArrowTask = new ShootArrowSimpleProjectileTask(entity);
                        return _shootArrowTask;
                    }
                }
                // LOS but no action taken — hold position, do NOT fall through to loot pickup
                setDebugState("Observing enemy...");
                return null;
            } else if (!injured) {
                setDebugState("PURSUE ENEMY!");
                if (_change_chain_priority) mod.getBehaviour().setUserTaskChainPriority(80);
                return new GetToEntityTask(entity);
            }
            // Active enemy present — never loot while in combat state
            return null;
        }

        if (injured) {
            setDebugState("Вы ранены и погибаете, остаётся ждать доктора");
            return null;
        }

        for (Item check : lootableItems(mod)) {
            if (mod.getEntityTracker().itemDropped(check)) {
                Optional<ItemEntity> closestEnt = mod.getEntityTracker().getClosestItemDrop(
                        ent -> mod.getEntityTracker().isEntityReachable(ent)
                                && mod.getPlayer().getPos().isInRange(ent.getEyePos(), 400)
                                // Skip loot that is too close to any known enemy — avoids oscillation bug
                                && !isLootNearEnemy(mod, ent, DANGER_RADIUS),
                        check);
                if (closestEnt.isPresent()) {
                    if (_change_chain_priority) mod.getBehaviour().setDefaultUserTaskChainPriority();
                    setDebugState("Сбор ресурсов для оружия");
                    _pickupTask = new PickupDroppedItemTask(new ItemTarget(check, 1), false);
                    return _pickupTask;
                }
            }
        }

        Random random = new Random();
        if (_chill_tactics == -1) {
            _chill_tactics = random.nextInt(2);
            _chill_tactics_changed = true;
        }

        setDebugState("Чилл");
        if (_change_chain_priority) mod.getBehaviour().setDefaultUserTaskChainPriority();
        return new IdleTask();
    }

    private boolean isReadyToPunk(AltoClef mod) {
        if (_role.equals(MurderRole.KILLER)) {
            return hasKillerWeapon(mod);
        } else {
            return ShootArrowSimpleProjectileTask.hasArrows(mod)
                    && ShootArrowSimpleProjectileTask.hasShootingWeapon(mod);
        }
    }

    public static boolean hasKillerWeapon(PlayerEntity entity) {
        for (Item weapon : ItemHelper.MMKillerWeapons) {
            if (entity.getMainHandStack().isOf(weapon)) return true;
        }
        return false;
    }

    public static boolean hasDetectiveWeapon(PlayerEntity entity) {
        for (Item weapon : ItemHelper.MMDetectiveWeapons) {
            if (entity.getMainHandStack().isOf(weapon)) return true;
        }
        return false;
    }

    public static boolean hasKillerWeapon(AltoClef mod) {
        return mod.getItemStorage().hasItemInventoryOnly(ItemHelper.MMKillerWeapons);
    }

    public static boolean isValidTargetMM(PlayerEntity player) {
        return isValidPlayerMM(player) && !isInjured(player);
    }

    public static boolean isValidPlayerMM(PlayerEntity player) {
        if (player == null || player.isDead() || !player.isAlive()) return false;
        if (player.isCreative() || player.isSpectator()) return false;
        if (player.isSleeping() || player.hasVehicle()) return false;
        if (player.isInvulnerable() || player.isInvisible()) return false;
        if (player.getName() == null) return false;
        return true;
    }

    private static boolean isInjured(PlayerEntity player) {
        if (player == null) return false;
        return player.hasVehicle();
    }

    private boolean shouldAvoid(AltoClef mod, PlayerEntity player) {
        return isValidTargetMM(player) && shouldAvoid(mod, player.getName().getString());
    }

    private boolean shouldAvoid(AltoClef mod, String name) {
        MurderRole role = _roles.get(name);
        if (_role.equals(MurderRole.KILLER)) {
            return false;
        } else {
            return Objects.equals(role, MurderRole.KILLER);
        }
    }

    private boolean isEnemy(AltoClef mod, PlayerEntity player) {
        return isValidTargetMM(player) && isEnemy(mod, player.getName().getString());
    }

    private boolean isEnemy(AltoClef mod, String name) {
        MurderRole role = _roles.get(name);
        if (_role.equals(MurderRole.KILLER)) {
            return true;
        } else {
            return Objects.equals(role, MurderRole.KILLER);
        }
    }

    /**
     * Returns true if the dropped item entity is within {@code radius} blocks of any known enemy.
     * Used to skip loot that would pull the bot back toward a dangerous player.
     */
    private boolean isLootNearEnemy(AltoClef mod, ItemEntity item, double radius) {
        return mod.getEntityTracker().getTrackedEntities(PlayerEntity.class).stream()
                .anyMatch(player -> isEnemy(mod, player) && player.getPos().isInRange(item.getPos(), radius));
    }

    private boolean useBow(AltoClef mod, Entity target) {
        return shouldBow(mod) && ShootArrowSimpleProjectileTask.canUseRanged(mod, target);
    }

    private boolean shouldBow(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.BOW)
                && (mod.getItemStorage().hasItem(Items.ARROW)
                || mod.getItemStorage().hasItem(Items.SPECTRAL_ARROW));
    }

    private static boolean shouldForce(Task task) {
        return task != null && task.isActive() && !task.isFinished();
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return false;
    }

    @Override
    protected String toDebugString() {
        if (_killerName == null || _role.equals(MurderRole.KILLER)) {
            return "MurderMystery: role " + _role.toString();
        } else {
            return "MurderMystery: KILLER IS " + _killerName + "!";
        }
    }
}
