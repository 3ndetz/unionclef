package adris.altoclef.control;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only record of vanilla's player-input attack, {@code MinecraftClient.doAttack}: the path a
 * pressed attack key takes (tapKey / holdKey / a human's mouse). Automatic combat calls the
 * interaction manager directly and is not in here. Filled by AttackDispatchMixin on the client
 * thread; read by Py4jEntryPoint.getInputDispatches. Nothing here changes what the game does.
 */
public final class AttackDispatchLog {
    private static final int CAP = 64;
    private static final ArrayDeque<Map<String, Object>> LOG = new ArrayDeque<>();
    private static long seq = 0;
    private static Map<String, Object> open = null;

    private AttackDispatchLog() {}

    /** Called at the head of doAttack. */
    public static synchronized void begin(int cooldown, HitResult target) {
        open = new HashMap<>();
        open.put("seq", ++seq);
        MinecraftClient mc = MinecraftClient.getInstance();
        open.put("tick", mc.world == null ? -1L : mc.world.getTime());
        open.put("cooldown", cooldown);   // > 0: vanilla refuses the attack (the miss-swing timer)
        open.put("refusedByCooldown", cooldown > 0);
        open.put("crosshair", describe(target));
    }

    /** Called at the return of doAttack with its raw return value. That value is NOT "the attack
     *  happened": vanilla returns true only when the press started breaking a block, and false for
     *  an entity attack and a miss swing alike (measured: a pig hit that cost it a heart read false).
     *  Kept as vanillaReturn; refusedByCooldown is the real "vanilla did nothing" signal. */
    public static synchronized void end(boolean vanillaReturn) {
        if (open == null) return;
        open.put("vanillaReturn", vanillaReturn);
        LOG.addLast(open);
        while (LOG.size() > CAP) LOG.removeFirst();
        open = null;
    }

    public static synchronized long lastSeq() {
        return seq;
    }

    /** Records with seq > sinceSeq, oldest first, at most limit (1..64). */
    public static synchronized List<Map<String, Object>> since(long sinceSeq, int limit) {
        int cap = limit <= 0 ? CAP : Math.min(limit, CAP);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : LOG) {
            if ((long) r.get("seq") > sinceSeq) out.add(new HashMap<>(r));
        }
        return out.size() > cap ? new ArrayList<>(out.subList(out.size() - cap, out.size())) : out;
    }

    /** The crosshair as a flat map with no nulls: type MISS / BLOCK / ENTITY / NONE. */
    public static Map<String, Object> describe(HitResult hr) {
        Map<String, Object> m = new HashMap<>();
        if (hr == null) {
            m.put("type", "NONE");
            return m;
        }
        m.put("type", hr.getType().name());
        if (hr instanceof EntityHitResult ehr && hr.getType() == HitResult.Type.ENTITY) {
            Entity e = ehr.getEntity();
            m.put("entityId", e.getId());
            m.put("entityType", Registries.ENTITY_TYPE.getId(e.getType()).toString());
            m.put("entityName", e.getName().getString());
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) m.put("distance", Math.round(mc.player.distanceTo(e) * 1000.0) / 1000.0);
        } else if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            m.put("pos", bhr.getBlockPos().getX() + "," + bhr.getBlockPos().getY() + "," + bhr.getBlockPos().getZ());
            m.put("side", bhr.getSide().asString());
        }
        return m;
    }
}
