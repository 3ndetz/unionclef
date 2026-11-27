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

    // ---- optional guard for ONE armed press (tapAttackGuarded) --------------------------------
    // Armed at the call after the same test passed there; checked again at the head of doAttack, on
    // the tick vanilla consumes the press, against the crosshair vanilla is about to attack. A
    // failed check cancels that one doAttack and is logged with its reason. Unarmed presses (tapKey,
    // tapKeyReceipt, a human's mouse) never pass through it.
    //
    // ⛔ THE GUARD BELONGS TO THE PRESS, NOT TO A TIME WINDOW (2026-11-27). It used to expire by WORLD
    // time (armed + 3) and an expired guard let the press through unchecked. World time on a client is
    // re-synced from the server: after any lag it jumps many ticks at once, and the press then hit
    // whatever was under the crosshair -- bench guard_race_probe.py under "tick sprint": 10 of 25
    // guarded presses dispatched "expired" onto a pig next to the target. Now: an armed guard is
    // always checked at dispatch; a press that arrives more than 3 CLIENT ticks late is cancelled once
    // ("delayed"); a press vanilla throws away (KeyBinding.unpressAll: a screen, a world change, focus
    // loss) takes its guard with it ("dropped", logged), so a later ordinary click is never affected;
    // every doAttack in the tick the press is consumed is checked, so no other press queued with it
    // can take its place.
    private static String guardUuid = null;
    private static boolean guardOtherTeam = false;
    private static long armedClientTick = -1;
    private static long checkedClientTick = -1;
    private static long clientTicks = 0;
    private static String lastGuard = "none";

    public static synchronized void arm(String uuid, boolean otherTeam, long nowTick) {
        guardUuid = uuid;
        guardOtherTeam = otherTeam;
        armedClientTick = clientTicks;
        checkedClientTick = -1;
    }

    public static synchronized void disarm() {
        guardUuid = null;
        checkedClientTick = -1;
    }

    /** Head of every client tick (ClientTickMixin): a guard already checked in an earlier tick is done. */
    public static synchronized void onClientTick() {
        clientTicks++;
        if (guardUuid != null && checkedClientTick >= 0 && clientTicks > checkedClientTick) disarm();
    }

    /** KeyBinding.unpressAll (a screen opened, a world change, focus lost): vanilla throws pending
     *  presses away, so the armed press is gone too -- drop its guard, with a receipt. */
    public static synchronized void onKeysReset() {
        if (guardUuid == null || checkedClientTick >= 0) return;
        Map<String, Object> r = new HashMap<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        r.put("seq", ++seq);
        r.put("tick", mc.world == null ? -1L : mc.world.getTime());
        r.put("guard", "dropped");
        r.put("refusedBy", "pressCleared");
        r.put("crosshair", describe(mc.crosshairTarget));
        r.put("vanillaReturn", false);
        LOG.addLast(r);
        while (LOG.size() > CAP) LOG.removeFirst();
        disarm();
    }

    /**
     * The guard's test, the same at the call and at dispatch: null = pass, else the reason --
     * notInGame, gui (a screen is open: vanilla does not attack then), cooldown (vanilla's attack
     * timer is running and it would refuse), noTarget (crosshair not on an entity), targetMismatch
     * (call) / targetChanged (dispatch) (another entity under the crosshair), teamUnknown (a team
     * check was asked and either side has no scoreboard team, or the target is not a player), ally
     * (same scoreboard team as self).
     */
    public static String verdict(MinecraftClient mc, String uuid, boolean otherTeam, int cooldown, boolean atDispatch) {
        if (mc.player == null || mc.world == null) return "notInGame";
        if (mc.currentScreen != null) return "gui";
        if (cooldown > 0) return "cooldown";
        HitResult hr = mc.crosshairTarget;
        if (!(hr instanceof EntityHitResult ehr) || hr.getType() != HitResult.Type.ENTITY) return "noTarget";
        Entity e = ehr.getEntity();
        if (uuid == null || !uuid.equals(e.getUuidAsString())) return atDispatch ? "targetChanged" : "targetMismatch";
        if (otherTeam) {
            if (!(e instanceof net.minecraft.entity.player.PlayerEntity)) return "teamUnknown";
            net.minecraft.scoreboard.Team mine = mc.player.getScoreboardTeam();
            net.minecraft.scoreboard.Team theirs = e.getScoreboardTeam();
            if (mine == null || theirs == null) return "teamUnknown";
            if (theirs.isEqual(mine)) return "ally";
        }
        return null;
    }

    /** At the head of doAttack: null when no guard is armed or it passes (then begin() follows);
     *  else the reason, the dispatch already logged as refused -- the caller cancels doAttack. */
    public static synchronized String checkAtDispatch(MinecraftClient mc, int cooldown, HitResult target) {
        if (guardUuid == null) { lastGuard = "none"; return null; }
        long now = mc.world == null ? -1L : mc.world.getTime();
        String why;
        if (checkedClientTick < 0 && clientTicks - armedClientTick > 3) {
            why = "delayed";                               // fail closed: see the note above
            checkedClientTick = clientTicks;               // the rest of this tick is checked too
        } else {
            if (checkedClientTick < 0) checkedClientTick = clientTicks;   // this tick's doAttacks all checked
            why = verdict(mc, guardUuid, guardOtherTeam, cooldown, true);
        }
        if (why == null) { lastGuard = "passed"; return null; }
        Map<String, Object> r = new HashMap<>();
        r.put("seq", ++seq);
        r.put("tick", now);
        r.put("cooldown", cooldown);
        r.put("refusedByCooldown", cooldown > 0);
        r.put("crosshair", describe(target));
        r.put("guard", "refused");
        r.put("refusedBy", why);
        r.put("vanillaReturn", false);
        LOG.addLast(r);
        while (LOG.size() > CAP) LOG.removeFirst();
        return why;
    }

    /** Called at the head of doAttack. */
    public static synchronized void begin(int cooldown, HitResult target) {
        open = new HashMap<>();
        open.put("seq", ++seq);
        open.put("guard", lastGuard);   // none / passed
        open.put("refusedBy", "none");
        lastGuard = "none";
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
            m.put("entityUuid", e.getUuidAsString());
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
