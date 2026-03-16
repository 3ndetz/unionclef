package adris.altoclef.multiversion;

import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.spawner.MobSpawnerLogic;
import net.minecraft.world.World;

//#if MC >= 11701
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.Random;
//#endif

public class MethodWrapper {



    public static Entity getRenderedEntity(MobSpawnerLogic logic, World world, BlockPos pos) {
        //#if MC>12002
        return logic.getRenderedEntity(world, pos);
        //#elseif MC >= 11904
        //$$ return logic.getRenderedEntity(world,Random.create() ,pos);
        //#elseif MC >= 11701
        //$$ return logic.getRenderedEntity(world);
        //#else
        //$$ return logic.getRenderedEntity();
        //#endif
    }

    public static float getDamageLeft(LivingEntity armorWearer, double damage, DamageSource source, double armor, double armorToughness) {
        return getDamageLeft(armorWearer, (float)damage,source,(float)armor,(float)armorToughness);
    }

    public static float getDamageLeft(LivingEntity armorWearer, float damage, DamageSource source, float armor, float armorToughness) {
        //#if MC >= 12100
        return DamageUtil.getDamageLeft(armorWearer, damage, source, armor, armorToughness);
        //#elseif MC>=12005
        //$$ return DamageUtil.getDamageLeft(damage, source, armor, armorToughness);
        //#else
        //$$ return DamageUtil.getDamageLeft(damage,armor,armorToughness);
        //#endif
    }



}
