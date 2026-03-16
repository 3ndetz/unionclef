package adris.altoclef.multiversion.versionedfields;

import net.minecraft.entity.Entity;

/**
 * A helper class implementing entities that are not yet supported in certain versions
 */
public class Entities {

    public static final Class<? extends Entity> UNSUPPORTED;
    public static final Class<? extends Entity> WARDEN;
    public static final Class<? extends Entity> GLOW_SQUID;

    static {
        UNSUPPORTED = VersionedFieldHelper.getUnsupportedEntityClass();

        //#if MC >= 11904
        WARDEN = net.minecraft.entity.mob.WardenEntity.class;
        //#else
        //$$ WARDEN = UNSUPPORTED;
        //#endif

        //#if MC >= 11701
        GLOW_SQUID = net.minecraft.entity.passive.GlowSquidEntity.class;
        //#else
        //$$ GLOW_SQUID = UNSUPPORTED;
        //#endif
    }



}
