package adris.altoclef.trackers.threats;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SneakEvent;
import adris.altoclef.eventbus.events.multiplayer.TeleportEvent;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.*;
import java.util.stream.Collectors;

public class ThreatTable {
    public AltoClef _mod;

    private final Map<Integer, String> entityIdToName = new HashMap<>();
    private final Map<String, PlayerThreat> playerThreats = new HashMap<>();

    public ThreatTable(AltoClef mod) {
        this._mod = mod;
        EventBus.subscribe(SneakEvent.class, evt -> onSneak(evt.entity, evt.sneak));
    }

    public void onSneak(Entity entity, boolean sneak) {
        if (entity instanceof PlayerEntity player) {
            PlayerThreat threat = playerThreats.get(player.getName().getString());
            if (threat != null) {
                if (sneak) {
                    threat.shiftTimer.reset();
                    threat.sneakRate += 1;
                }
                if (threat.sneakRate > 5 && threat.name != null) {
                    if (threat.sneakRate == 6 && _mod.getInfoSender() != null) {
                        // onAutoclefEvent stub
                    }
                    avoid(threat.name);
                }
            }
        }
    }

    public void clearWorldData() {
        playerThreats.clear();
        entityIdToName.clear();
    }

    public int get(String name) {
        return playerThreats.getOrDefault(name, new PlayerThreat(-1)).id;
    }

    public PlayerThreat getPlayerThreat(String name) {
        return playerThreats.getOrDefault(name, null);
    }

    public void registerPlayer(int entityId, boolean updateData) {
        if (playerThreats.entrySet().stream().anyMatch(e -> e.getValue().id == entityId)) return;
        if (_mod.getWorld() == null) return;
        Entity entity = _mod.getWorld().getEntityById(entityId);
        if (entity instanceof PlayerEntity player) {
            String playerName = entity.getName().getString();
            if (playerName != null) {
                entityIdToName.put(entityId, playerName);
                playerThreats.putIfAbsent(playerName, new PlayerThreat(entityId));
                if (updateData) updatePlayerData(playerName, player, false);
            }
        }
    }

    public void registerPlayer(int entityId) {
        registerPlayer(entityId, false);
    }

    public void recordRangedAttack(int attackerEntityId) {
        registerPlayer(attackerEntityId);
        String attackerName = entityIdToName.get(attackerEntityId);
        if (attackerName != null) {
            PlayerThreat threat = playerThreats.get(attackerName);
            threat.lastShootTimer.reset();
        }
    }

    public void recordAttackAnimation(int attackerEntityId) {
        registerPlayer(attackerEntityId);
        String attackerName = entityIdToName.get(attackerEntityId);
        if (attackerName != null) {
            PlayerThreat threat = playerThreats.get(attackerName);
            threat.lastAttackTimer.reset();
            for (Map.Entry<String, PlayerThreat> entry : playerThreats.entrySet()) {
                if (!entry.getKey().equals(attackerName)) {
                    double lookingProbability = LookHelper.getLookingProbability(
                            threat.lastPos, entry.getValue().lastPos, threat.lastRotationVec);
                    if (lookingProbability > 0.7) {
                        entry.getValue().addPotentialAttacker(attackerEntityId);
                    }
                }
            }
        }
    }

    public double compareThreatProbablity(PlayerThreat a, PlayerThreat c) {
        if (a != null && a.lastPos != null && a.lastRotationVec != null && c != null && c.lastPos != null) {
            double score = LookHelper.getLookingProbability(a.lastPos, c.lastPos, a.lastRotationVec);
            double distance = a.lastPos.distanceTo(c.lastPos);
            if (distance <= 10) {
                score += a.weaponThreat.equals(WeaponThreat.Melee) ? (10 - distance) / 10 : (10 - distance) / 20;
            } else if (distance <= 100) {
                score += a.weaponThreat.equals(WeaponThreat.Ranged) ? (100 - distance) / 1000 : (100 - distance) / 80;
            } else {
                score -= 0.5;
            }
            return score;
        }
        return 0;
    }

    public int compareThreatsProbablity(PlayerThreat a, PlayerThreat b, PlayerThreat c) {
        if (a != null && a.lastPos != null && a.lastRotationVec != null &&
                b != null && b.lastPos != null && b.lastRotationVec != null && c != null && c.lastPos != null) {
            return Double.compare(compareThreatProbablity(b, c), compareThreatProbablity(a, c));
        }
        return 0;
    }

    public ArrayList<PlayerThreat> getAllRecentAttackers(String damagedName, boolean sorted) {
        ArrayList<PlayerThreat> recentAttackers = new ArrayList<>(playerThreats.entrySet().stream()
                .filter(a -> !a.getValue().lastAttackTimer.elapsed() && !a.getKey().equals(damagedName))
                .map(Map.Entry::getValue).collect(Collectors.toList()));
        if (!recentAttackers.isEmpty() && sorted) {
            PlayerThreat threat = playerThreats.get(damagedName);
            if (threat != null) {
                recentAttackers.sort((a, b) -> compareThreatsProbablity(a, b, threat));
            }
        }
        return recentAttackers;
    }

    public ArrayList<PlayerThreat> getAllRecentAttackers(String damagedName) {
        return getAllRecentAttackers(damagedName, true);
    }

    public PlayerThreat getLastAttacker(String damagedName, boolean writeNew) {
        ArrayList<PlayerThreat> recentAttackers = getAllRecentAttackers(damagedName);
        PlayerThreat threat = playerThreats.get(damagedName);
        if (!recentAttackers.isEmpty()) {
            PlayerThreat lastAttackerThreat = recentAttackers.get(0);
            if (lastAttackerThreat.id != -1) {
                if (writeNew && threat != null) threat.lastAttackerEntityId = lastAttackerThreat.id;
                return lastAttackerThreat;
            }
        }
        return null;
    }

    public void recordDamage(int damagedEntityId) {
        registerPlayer(damagedEntityId);
        String damagedName = entityIdToName.get(damagedEntityId);
        if (damagedName != null) {
            PlayerThreat threat = playerThreats.get(damagedName);
            threat.damagedTimer.reset();
            threat.lastDamagedTimer.reset();
        }
    }

    public int recordDamageConfirmed(int damagedEntityId, float amount) {
        String damagedName = entityIdToName.get(damagedEntityId);
        if (damagedName != null) {
            PlayerThreat threat = playerThreats.get(damagedName);
            if (threat != null && threat.id != -1 && threat.name != null) {
                threat.lastDamageAmount = amount;
                threat.combatEngagementTimer.reset();
                if (!threat.combatEngagementTimer.elapsed()) {
                    threat.cumulativeDamage += amount;
                } else {
                    threat.cumulativeDamage = amount;
                }
                PlayerThreat attackerThreat = getLastAttacker(damagedName, true);
                if (attackerThreat != null && attackerThreat.id != -1) {
                    if (attackerThreat.name != null && !attackerThreat.name.isBlank() &&
                            threat.name != null && !threat.name.isBlank()) {
                        if (_mod.getPlayer() != null && _mod.getPlayer().getName() != null &&
                                threat.name.equals(_mod.getPlayer().getName().getString())) {
                            pursue(attackerThreat.name);
                        }
                    }
                    threat.combatEngagementTimer.reset();
                    threat.addDamageRecord(attackerThreat.id, amount);
                    return attackerThreat.id;
                }
            }
        }
        return -1;
    }

    public void updatePlayerData(String playerName, PlayerEntity entity, boolean register) {
        if (register) registerPlayer(entity.getId());
        PlayerThreat threat = playerThreats.get(playerName);
        if (threat != null) {
            int entityId = entity.getId();
            if (threat.id != entityId) {
                entityIdToName.put(entityId, playerName);
                entityIdToName.remove(threat.id);
                threat.id = entityId;
            }
            if (threat.sneak != entity.isSneaking()) {
                if (threat.name != null && _mod.getPlayer() != null && _mod.getPlayer().getName() != null
                        && !threat.name.equals(_mod.getPlayer().getName().getString())) {
                    EventBus.publish(new SneakEvent(entity, entity.isSneaking()));
                }
                threat.sneak = entity.isSneaking();
            }
            if (threat.sneakRate > 0 && threat.shiftTimer.elapsed()) threat.sneakRate = 0;
            if (entity.getPos() != null) {
                if (threat.lastPos != null && threat.lastPos.distanceTo(entity.getPos()) > 10) {
                    EventBus.publish(new TeleportEvent(entity, threat.lastPos, entity.getPos()));
                }
                threat.lastPos = entity.getPos();
            }
            threat.weaponThreat = ItemHelper.getWeaponThreat(_mod, entity);
            threat.lastHealth = entity.getHealth();
            threat.lastRotationVec = entity.getRotationVec(0);
            threat.name = entity.getName().getString();
        }
    }

    public void updatePlayerData(String playerName, PlayerEntity entity) {
        updatePlayerData(playerName, entity, true);
    }

    public boolean isInCombat(String playerName) {
        PlayerThreat threat = playerThreats.get(playerName);
        return threat != null && (!threat.combatEngagementTimer.elapsed() ||
                !threat.lastAttackTimer.elapsed() || !threat.lastDamagedTimer.elapsed());
    }

    public boolean shouldAvoid(String playerName) {
        PlayerThreat threat = playerThreats.get(playerName);
        return threat != null && !threat.shouldAvoidTimer.elapsed();
    }

    public boolean shouldAttack(String playerName) {
        PlayerThreat threat = playerThreats.get(playerName);
        return threat != null && !threat.shouldKillTimer.elapsed();
    }

    public boolean isSelfThreat(PlayerThreat threat) {
        return threat.name != null && Objects.equals(AltoClef.getSelfName(), threat.name);
    }

    public boolean forget() {
        for (PlayerThreat threat : playerThreats.values()) forget(threat);
        return true;
    }

    public boolean forget(PlayerThreat threat) {
        if (threat != null && !isSelfThreat(threat)) {
            threat.shouldAvoidTimer.forceElapse();
            threat.shouldKillTimer.forceElapse();
            return true;
        }
        return false;
    }

    public boolean forget(String playerName) {
        return forget(playerThreats.getOrDefault(playerName, null));
    }

    public boolean avoid(String playerName) {
        PlayerThreat threat = playerThreats.get(playerName);
        if (threat != null && !isSelfThreat(threat)) {
            threat.shouldAvoidTimer.reset();
            return true;
        }
        return false;
    }

    public boolean pursue(String playerName) {
        PlayerThreat threat = playerThreats.get(playerName);
        if (threat != null && !isSelfThreat(threat)) {
            threat.shouldKillTimer.reset();
            return true;
        }
        return false;
    }

    public String getLastAttacker(String playerName) {
        PlayerThreat threat = playerThreats.get(playerName);
        if (threat != null && !threat.lastDamagedTimer.elapsed()) {
            return entityIdToName.get(threat.lastAttackerEntityId);
        }
        return null;
    }

    public float getCumulativeDamage(String playerName) {
        PlayerThreat threat = playerThreats.get(playerName);
        return threat != null ? threat.cumulativeDamage : 0f;
    }

    public float getCurrentHealth(String playerName) {
        PlayerThreat threat = playerThreats.get(playerName);
        return threat != null ? threat.lastHealth : 20.0f;
    }

    public String getRelevantThreats() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, PlayerThreat> entry : playerThreats.entrySet()) {
            PlayerThreat threat = entry.getValue();
            if (!threat.combatEngagementTimer.elapsed() || !threat.lastAttackTimer.elapsed() || !threat.lastDamagedTimer.elapsed()) {
                sb.append(entry.getKey()).append(" ");
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("=== Player Threat Table Status ===\n");
        if (playerThreats.isEmpty()) {
            sb.append("No players registered.\n");
            return sb.toString();
        }
        for (Map.Entry<String, PlayerThreat> entry : playerThreats.entrySet()) {
            PlayerThreat threat = entry.getValue();
            sb.append(entry.getKey()).append(": health=")
              .append(String.format("%.0f", threat.lastHealth))
              .append(isInCombat(entry.getKey()) ? " IN COMBAT" : " PEACEFUL").append("\n");
        }
        return sb.toString();
    }
}
