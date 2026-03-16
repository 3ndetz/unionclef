/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package shredder.utils;

import shredder.Baritone;
import shredder.altoclef.AltoClefSettings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.EnchantmentEffectComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.enchantment.effect.AttributeEnchantmentEffect;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.registry.entry.RegistryEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A cached list of the best tools on the hotbar for any block
 *
 * @author Avery, Brady, leijurv
 */
public class ToolSet {

    /**
     * A cache mapping a {@link Block} to how long it will take to break
     * with this toolset, given the optimum tool is used.
     */
    private final Map<Block, Double> breakStrengthCache;

    /**
     * My buddy leijurv owned me so we have this to not create a new lambda instance.
     */
    private final Function<Block, Double> backendCalculation;

    private final ClientPlayerEntity player;

    public ToolSet(ClientPlayerEntity player) {
        breakStrengthCache = new HashMap<>();
        this.player = player;

        if (Baritone.settings().considerPotionEffects.value) {
            double amplifier = potionAmplifier();
            Function<Double, Double> amplify = x -> amplifier * x;
            backendCalculation = amplify.compose(this::getBestDestructionTime);
        } else {
            backendCalculation = this::getBestDestructionTime;
        }
    }

    /**
     * Using the best tool on the hotbar, how fast we can mine this block
     *
     * @param state the blockstate to be mined
     * @return the speed of how fast we'll mine it. 1/(time in ticks)
     */
    public double getStrVsBlock(BlockState state) {
        return breakStrengthCache.computeIfAbsent(state.getBlock(), backendCalculation);
    }

    /**
     * Evaluate the material cost of a possible tool. The priority matches the
     * harvest level order; there is a chance for multiple at the same with modded tools
     * but in that case we don't really care.
     *
     * @param itemStack a possibly empty ItemStack
     * @return values from 0 up
     */
    private int getMaterialCost(ItemStack itemStack) {
        if (itemStack.getItem() instanceof ToolItem) {
            ToolItem tool = (ToolItem) itemStack.getItem();
            return (int) tool.getMaterial().getAttackDamage();
        } else {
            return -1;
        }
    }

    public boolean hasSilkTouch(ItemStack stack) {
        ItemEnchantmentsComponent enchantments = stack.getEnchantments();
        for (RegistryEntry<Enchantment> enchant : enchantments.getEnchantments()) {
            // silk touch enchantment is still special cased as affecting block drops
            // not possible to add custom attribute via datapack
            if (enchant.matchesKey(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate which tool on the hotbar is best for mining, depending on an override setting,
     * related to auto tool movement cost, it will either return current selected slot, or the best slot.
     *
     * @param b the blockstate to be mined
     * @return An int containing the index in the tools array that worked best
     */

    public int getBestSlot(Block b, boolean preferSilkTouch) {
        return getBestSlot(b, preferSilkTouch, false);
    }

    public int getBestSlot(Block b, boolean preferSilkTouch, boolean pathingCalculation) {

        /*
        If we actually want know what efficiency our held item has instead of the best one
        possible, this lets us make pathing depend on the actual tool to be used (if auto tool is disabled)
        */
        if (b.getBlastResistance() == 0) return(player.getInventory().selectedSlot);

        if (!Baritone.settings().autoTool.value && pathingCalculation) {
            return player.getInventory().selectedSlot;
        }

        int best = 0;
        double highestSpeed = Double.NEGATIVE_INFINITY;
        int lowestCost = Integer.MIN_VALUE;
        boolean bestSilkTouch = false;
        BlockState blockState = b.getDefaultState();
        for (int i = 0; i < 9; i++) {
            ItemStack itemStack = player.getInventory().getStack(i);
            if (!Baritone.settings().useSwordToMine.value && itemStack.getItem() instanceof SwordItem) {
                continue;
            }
            if (Baritone.settings().itemSaver.value && (itemStack.getDamage() + Baritone.settings().itemSaverThreshold.value) >= itemStack.getMaxDamage() && itemStack.getMaxDamage() > 1) {
                continue;
            }
            if (AltoClefSettings.getInstance().shouldForceSaveTool(blockState, itemStack)) {
                continue;
            }
            double speed = calculateSpeedVsBlock(itemStack, blockState);
            boolean silkTouch = hasSilkTouch(itemStack);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                best = i;
                lowestCost = getMaterialCost(itemStack);
                bestSilkTouch = silkTouch;
            } else if (speed == highestSpeed) {
                int cost = getMaterialCost(itemStack);
                if ((cost < lowestCost && (silkTouch || !bestSilkTouch)) ||
                        (preferSilkTouch && !bestSilkTouch && silkTouch)) {
                    highestSpeed = speed;
                    best = i;
                    lowestCost = cost;
                    bestSilkTouch = silkTouch;
                }
            }
        }
        return best;
    }

    /**
     * Calculate how effectively a block can be destroyed
     *
     * @param b the blockstate to be mined
     * @return A double containing the destruction ticks with the best tool
     */
    private double getBestDestructionTime(Block b) {
        ItemStack stack = player.getInventory().getStack(getBestSlot(b, false, true));
        return calculateSpeedVsBlock(stack, b.getDefaultState()) * avoidanceMultiplier(b);
    }

    private double avoidanceMultiplier(Block b) {
        return Baritone.settings().blocksToAvoidBreaking.value.contains(b) ? Baritone.settings().avoidBreakingMultiplier.value : 1;
    }

    /**
     * Calculates how long would it take to mine the specified block given the best tool
     * in this toolset is used. A negative value is returned if the specified block is unbreakable.
     *
     * @param item  the item to mine it with
     * @param state the blockstate to be mined
     * @return how long it would take in ticks
     */
    public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
        float hardness;
        try {
            hardness = state.getHardness(null, null);
        } catch (NullPointerException npe) {
            // can't easily determine the hardness so treat it as unbreakable
            return -1;
        }
        if (hardness < 0) {
            return -1;
        }

        float speed = item.getMiningSpeedMultiplier(state);
        if (speed > 1) {
            final ItemEnchantmentsComponent itemEnchantments = item.getEnchantments();
            OUTER: for (RegistryEntry<Enchantment> enchant : itemEnchantments.getEnchantments()) {
                List<AttributeEnchantmentEffect> effects = enchant.value().getEffect(EnchantmentEffectComponentTypes.ATTRIBUTES);
                for (AttributeEnchantmentEffect e : effects) {
                    if (e.attribute().matchesKey(EntityAttributes.PLAYER_MINING_EFFICIENCY.getKey().get())) {
                        speed += e.amount().getValue(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }

        if (AltoClefSettings.getInstance().shouldForceUseTool(state, item)) {
            return Double.POSITIVE_INFINITY;
        }
        speed /= hardness;
        if (!state.isToolRequired() || (!item.isEmpty() && item.isSuitableFor(state))) {
            return speed / 30;
        } else {
            return speed / 100;
        }
    }

    /**
     * Calculates any modifier to breaking time based on status effects.
     *
     * @return a double to scale block breaking speed.
     */
    private double potionAmplifier() {
        double speed = 1;
        if (player.hasStatusEffect(StatusEffects.HASTE)) {
            speed *= 1 + (player.getStatusEffect(StatusEffects.HASTE).getAmplifier() + 1) * 0.2;
        }
        if (player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
            switch (player.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier()) {
                case 0:
                    speed *= 0.3;
                    break;
                case 1:
                    speed *= 0.09;
                    break;
                case 2:
                    speed *= 0.0027; // you might think that 0.09*0.3 = 0.027 so that should be next, that would make too much sense. it's 0.0027.
                    break;
                default:
                    speed *= 0.00081;
                    break;
            }
        }
        return speed;
    }
}
