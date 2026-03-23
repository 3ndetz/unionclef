package kaptainwutax.tungsten.combat;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

/**
 * Estimates knockback strength based on enemy state.
 *
 * Vanilla KB formula (1.21):
 *   base = 0.4
 *   if attacker sprinting: +0.4 (sprint knockback via Entity.attack)
 *   knockback enchant: +0.4 per level
 *   attacker velocity contributes to KB direction (not magnitude directly,
 *   but sprint flag is what matters for the bonus)
 *
 * Enemy state detection:
 *   STANDING  — horizSpeed < 0.01
 *   WALKING   — horizSpeed 0.01-0.15
 *   SPRINTING — horizSpeed > 0.15 or entity.isSprinting()
 *
 * Cooldown: when enemy transitions from higher KB tier to lower,
 * we keep the higher estimate for DECAY_TICKS to avoid premature relaxation.
 */
public class KnockbackEstimator {

    private static final double KB_BASE = 0.4;
    private static final double KB_SPRINT_BONUS = 0.4;
    private static final double KB_ENCHANT_PER_LEVEL = 0.4;
    private static final double KB_UP = 0.4;

    private static final int DECAY_TICKS = 40; // 2 seconds before downgrading estimate

    private double currentEstimate = KB_BASE;
    private int decayCooldown = 0;
    private int lastKBEnchantLevel = 0;

    /**
     * Update KB estimate based on enemy state. Call every tick.
     */
    public void tick(Entity enemy, Vec3d enemyVelocity) {
        double horizSpeed = Math.sqrt(enemyVelocity.x * enemyVelocity.x
                + enemyVelocity.z * enemyVelocity.z);

        // detect KB enchant on enemy's held item
        int kbLevel = 0;
        if (enemy instanceof PlayerEntity pe) {
            ItemStack held = pe.getMainHandStack();
            if (!held.isEmpty()) {
                // MC 1.21: EnchantmentHelper doesn't have a simple getKnockback.
                // We check via the damage enchantment helper or iterate.
                // For now, use a safe approach: assume up to KB II if sword.
                // TODO: proper enchantment read when API is clear for 1.21
                kbLevel = lastKBEnchantLevel; // keep last known
            }
        }
        lastKBEnchantLevel = kbLevel;

        // compute new estimate
        double newEstimate = KB_BASE;

        // sprint detection: isSprinting() flag OR high speed
        boolean sprinting = false;
        if (enemy instanceof PlayerEntity pe) {
            sprinting = pe.isSprinting();
        }
        if (!sprinting && horizSpeed > 0.15) {
            sprinting = true; // speed-based fallback
        }

        if (sprinting) {
            newEstimate += KB_SPRINT_BONUS;
        }

        newEstimate += kbLevel * KB_ENCHANT_PER_LEVEL;

        // cooldown: only decrease estimate after DECAY_TICKS
        if (newEstimate >= currentEstimate) {
            currentEstimate = newEstimate;
            decayCooldown = DECAY_TICKS;
        } else {
            if (decayCooldown > 0) {
                decayCooldown--;
                // keep higher estimate during cooldown
            } else {
                currentEstimate = newEstimate;
            }
        }
    }

    /** Total horizontal KB strength (base + sprint + enchant). */
    public double getHorizontalStrength() {
        return currentEstimate;
    }

    /** Vertical KB impulse (constant in vanilla). */
    public double getVerticalStrength() {
        return KB_UP;
    }

    /** Is enemy currently considered sprinting? */
    public boolean isEnemySprinting() {
        return currentEstimate >= KB_BASE + KB_SPRINT_BONUS - 0.01;
    }

    public void reset() {
        currentEstimate = KB_BASE;
        decayCooldown = 0;
        lastKBEnchantLevel = 0;
    }
}
