package adris.altoclef.multiversion;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.world.World;

public class DamageSourceVer {


    public static DamageSource getFallDamageSource(World world) {
        //#if MC >= 11904
        return world.getDamageSources().fall();
        //#else
        //$$ return DamageSource.FALL;
        //#endif
    }

}
