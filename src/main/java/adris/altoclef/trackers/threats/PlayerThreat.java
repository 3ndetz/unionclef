package adris.altoclef.trackers.threats;

import adris.altoclef.util.time.TimerReal;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerThreat {
    public PlayerThreat(int new_id) {
        this.id = new_id;
    }

    public int id;
    public String name;
    public double combatTime = 10;
    public double damagedTime = 0.4;
    public final TimerReal lastAttackTimer = new TimerReal(damagedTime);
    public final TimerReal lastShootTimer = new TimerReal(1);
    public final TimerReal lastDamagedTimer = new TimerReal(damagedTime);
    public final TimerReal damagedTimer = new TimerReal(damagedTime);
    public final TimerReal combatEngagementTimer = new TimerReal(combatTime);
    public TimerReal shouldAvoidTimer = new TimerReal(10);
    public TimerReal shouldKillTimer = new TimerReal(30);
    public int lastAttackerEntityId = -1;
    public float lastDamageAmount = 0;
    public float cumulativeDamage = 0;
    public float lastHealth = 20.0f;
    public Vec3d lastPos;
    public WeaponThreat weaponThreat = WeaponThreat.Harmless;
    public Vec3d lastRotationVec;
    public boolean sneak = false;
    public int sneakRate = 0;
    public final TimerReal shiftTimer = new TimerReal(3);

    public final Map<Integer, TimerReal> potentialAttackers = new HashMap<>();
    public final Map<Integer, Float> damageTable = new HashMap<>();

    public void addPotentialAttacker(int entityId) {
        potentialAttackers.putIfAbsent(entityId, new TimerReal(2.0));
        potentialAttackers.get(entityId).reset();
    }

    public void addDamageRecord(int entityId, float damage) {
        damageTable.putIfAbsent(entityId, damage);
    }

    public Map<Integer, Float> getDamageTable() {
        return damageTable;
    }

    public List<Integer> getRecentAttackers() {
        List<Integer> recent = new ArrayList<>();
        for (Map.Entry<Integer, TimerReal> entry : potentialAttackers.entrySet()) {
            if (!entry.getValue().elapsed()) {
                recent.add(entry.getKey());
            }
        }
        return recent;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof PlayerThreat threat) {
            return threat.id == this.id && this.name.equals(threat.name);
        }
        return super.equals(obj);
    }

    @Override
    public String toString() {
        return "Player{Entityid=" + id + ", nick='" + name + "', health=" + lastHealth +
                ", pos=" + lastPos + ", hasWeapon=" + weaponThreat + '}';
    }
}
