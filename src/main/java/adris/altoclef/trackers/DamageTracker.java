package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.*;
import adris.altoclef.eventbus.events.multiplayer.ItemUseEvent;
import adris.altoclef.tasks.multiplayer.minigames.MurderMysteryTask;
import adris.altoclef.trackers.threats.DamageTrackerStrategy;
import adris.altoclef.trackers.threats.PlayerThreat;
import adris.altoclef.trackers.threats.ThreatTable;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerReal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.*;

public class DamageTracker extends Tracker {
    private final HashMap<String, PlayerEntity> _playerMap = new HashMap<>();
    private final HashMap<String, Float> _prevPlayerHealth = new HashMap<>();
    private final TimerReal _recentDamageTimer = new TimerReal(0.05);
    private final TimerReal _deathEventSpamTimer = new TimerReal(0.5);
    private List<AbstractClientPlayerEntity> _prevPlayerList = new ArrayList<>();
    public String _lastAttackingPlayerName = "undefined";
    public String _lastKilled = "";
    private double _lastAttackingPlayerIsLookingProbability;
    private double _lastAttackingPlayerMyLookingProbability;
    public final TimerReal _attackCheckTimer = new TimerReal(0.7);
    private PlayerEntity _attackerCheck;
    private boolean _attackerCheckHit = false;
    private final HashMap<String, TimerReal> _playerDamageTimers = new HashMap<>();
    private final float DEATH_HEALTH_THRESHOLD = 10.0f;
    private final float FULL_HEALTH = 20.0f;
    public ThreatTable threatTable;

    public DamageTracker(TrackerManager manager) {
        super(manager); // sets this.mod via TrackerManager.addTracker
        threatTable = new ThreatTable(mod);
        EventBus.subscribe(ClientDamageEvent.class, evt -> onClientDamage());
        EventBus.subscribe(ClientHandSwingEvent.class, evt -> onClientHandSwing());
        EventBus.subscribe(DamageEvent.class, evt -> onAnyDamage(evt._entity));
        EventBus.subscribe(AnimEvent.class, evt -> onSwing(evt._entity, evt._type));
        EventBus.subscribe(DeathEvent.class, evt -> checkOnClientKills(evt.name, evt.attacker));
        EventBus.subscribe(ItemUseEvent.class, evt -> onSwing(evt.entity, AnimType.SWING_MAIN_HAND));
    }

    public void onClientHandSwing() {
        LivingEntity attacking = mod.getPlayer().getAttacking();
        if (attacking != null) {
            recordOnSwing(mod.getPlayer());
        }
    }

    public void recordOnSwing(Entity entity) {
        if (mod.getBehaviour().getDamageTrackerStrategy().equals(DamageTrackerStrategy.MurderMystery)) {
            if (entity instanceof PlayerEntity player && MurderMysteryTask.hasKillerWeapon(player)) {
                threatTable.recordAttackAnimation(entity.getId());
            }
            return;
        }
        threatTable.recordAttackAnimation(entity.getId());
    }

    public void onClientDamage() {
        _recentDamageTimer.reset();
        onAnyDamage(mod.getPlayer());
    }

    public void onAnyDamage(Entity entity) {
        threatTable.recordDamage(entity.getId());
    }

    public void onSwing(Entity entity, AnimType type) {
        if (type.equals(AnimType.SWING_MAIN_HAND)) {
            recordOnSwing(entity);
        }
    }

    private void updatePlayerDamageTimer(String playerName) {
        _playerDamageTimers.computeIfAbsent(playerName, k -> new TimerReal(2.0));
        _playerDamageTimers.get(playerName).reset();
    }

    public boolean wasRecentlyDamaged(String name) {
        return threatTable.isInCombat(name);
    }

    public void onClientDeath(String killername) {
        Debug.logMessage("confirmed death from " + killername);
        if (mod.getInfoSender() != null && !killername.equals("undefined")) {
            mod.getInfoSender().onDeath(killername);
        }
    }

    public void onClientKill(String name) {
        Debug.logMessage("confirmed kill -" + name);
        if (mod.getInfoSender() != null && !name.equals("undefined")) mod.getInfoSender().onKill(name);
    }

    public ThreatTable getThreatTable() {
        return threatTable;
    }

    public String getThreatStatus() {
        return threatTable.toString();
    }

    public void onDamage(String name, float amount) {
        if (mod.getInfoSender() != null && name.equals(mod.getPlayer().getName().getString())) {
            mod.getInfoSender().onDamage(amount);
        }
        if (amount > 1 && name.equals(_lastAttackingPlayerName) && !_attackCheckTimer.elapsed()) {
            Debug.logInternal("Урон по " + _lastAttackingPlayerName + " прошел!");
            _attackerCheckHit = false;
        }
        int id = threatTable.get(name);
        if (id != -1) {
            threatTable.recordDamageConfirmed(id, amount);
        }
        String att_name = threatTable.getLastAttacker(name);
        if (mod.getInfoSender() != null && att_name != null) {
            mod.getInfoSender().onDamageConfirmed(name, att_name, amount);
        }
    }

    public void onChangeHealth(String name, float oldHealth, float newHealth) {
        float healthDelta = newHealth - oldHealth;
        if (wasRecentlyDamaged(name)) {
            if (newHealth <= 0.0f || (newHealth >= FULL_HEALTH && oldHealth <= DEATH_HEALTH_THRESHOLD) ||
                    healthDelta > DEATH_HEALTH_THRESHOLD) {
                onDeath(name);
                return;
            }
        }
        if (healthDelta < 0) {
            updatePlayerDamageTimer(name);
            onDamage(name, -healthDelta);
        }
    }

    public void onClientMeleeAttack(Entity target) {
        if (target instanceof PlayerEntity player) {
            _attackerCheck = player;
            _attackCheckTimer.reset();
            _attackerCheckHit = true;
        }
    }

    private void checkOnClientKills(String name, String killerName) {
        if (mod.getPlayer().getName().getString().equals(name)) {
            onClientDeath(killerName);
        } else if (isPlayerKill(name)) {
            onClientKill(name);
        }
    }

    private void onDeath(String name, String killerName) {
        if (!killerName.equals("undefined")) {
            if (_lastKilled.equals(name)) {
                if (_deathEventSpamTimer.elapsed()) {
                    _deathEventSpamTimer.reset();
                    Debug.logMessage("New kill: " + killerName + " -> " + name);
                    EventBus.publish(new DeathEvent(name, killerName));
                }
            } else {
                Debug.logMessage("New kill: " + killerName + " -> " + name);
                EventBus.publish(new DeathEvent(name, killerName));
            }
            _lastKilled = name;
        }
        _playerDamageTimers.remove(name);
    }

    private void onDeath(String name) {
        onDeath(name, determineKiller(name));
    }

    public void onPlayerRemove(AbstractClientPlayerEntity player) {
        if (player == null) return;
        String playerName = player.getName().getString();
        float lastHealth = _prevPlayerHealth.getOrDefault(playerName, FULL_HEALTH);
        DamageTrackerStrategy strategy = mod.getBehaviour().getDamageTrackerStrategy();
        switch (strategy) {
            case Smart:
                if (wasRecentlyDamaged(playerName)) onDeath(playerName);
                break;
            case Vanilla:
                if (lastHealth <= DEATH_HEALTH_THRESHOLD && wasRecentlyDamaged(playerName)) onDeath(playerName);
                break;
            case MurderMystery:
                PlayerThreat threat = threatTable.getLastAttacker(playerName, true);
                if (threat != null && threat.name != null) onDeath(playerName, threat.name);
                else onDeath(playerName);
                break;
            default:
                break;
        }
    }

    private String determineKiller(String name) {
        PlayerThreat attackerThreat = threatTable.getLastAttacker(name, false);
        if (attackerThreat != null && attackerThreat.name != null) return attackerThreat.name;
        return "undefined";
    }

    private boolean isPlayerKill(String name) {
        return _lastAttackingPlayerName != null &&
                _lastAttackingPlayerName.equals(name) &&
                _lastAttackingPlayerMyLookingProbability > 0.70D;
    }

    public void tick() {
        if (!AltoClef.inGame() || MinecraftClient.getInstance().world == null) return;
        List<AbstractClientPlayerEntity> currentPlayers = MinecraftClient.getInstance().world.getPlayers();
        if (!_prevPlayerList.equals(currentPlayers)) {
            Set<AbstractClientPlayerEntity> removedPlayers = new HashSet<>(_prevPlayerList);
            removedPlayers.removeAll(currentPlayers);
            for (AbstractClientPlayerEntity p : removedPlayers) onPlayerRemove(p);
            _prevPlayerList = new ArrayList<>(currentPlayers);
            updatePlayerStates(currentPlayers);
        }
        for (AbstractClientPlayerEntity player : currentPlayers) {
            if (player != null && player.getName() != null) {
                String name = player.getName().getString();
                threatTable.updatePlayerData(name, player);
                float prevHealth = _prevPlayerHealth.getOrDefault(name, player.getHealth());
                float currentHealth = player.getHealth();
                if (prevHealth != currentHealth) {
                    onChangeHealth(name, prevHealth, currentHealth);
                    _prevPlayerHealth.put(name, currentHealth);
                }
            }
        }
        updateAttackingPlayerInfo();
    }

    private void updatePlayerStates(List<AbstractClientPlayerEntity> currentPlayers) {
        for (AbstractClientPlayerEntity player : currentPlayers) {
            if (player != null && player.getName() != null) {
                String name = player.getName().getString();
                _playerMap.put(name, player);
                _prevPlayerHealth.putIfAbsent(name, player.getHealth());
            }
        }
    }

    private void updateAttackingPlayerInfo() {
        LivingEntity attacking = mod.getPlayer().getAttacking();
        if (attacking instanceof PlayerEntity player) {
            _lastAttackingPlayerName = attacking.getName().getString();
            _lastAttackingPlayerIsLookingProbability = LookHelper.getLookingProbability(player, mod.getPlayer());
            _lastAttackingPlayerMyLookingProbability = LookHelper.getLookingProbability(mod.getPlayer(), player);
        }
    }

    @Override
    protected synchronized void updateState() {
    }

    @Override
    protected void reset() {
        threatTable.clearWorldData();
        _prevPlayerHealth.clear();
        _playerMap.clear();
    }

    public List<AbstractClientPlayerEntity> getPlayerList() {
        return _prevPlayerList;
    }
}
