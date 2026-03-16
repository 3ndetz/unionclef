package adris.altoclef.multiversion;

import net.minecraft.entity.damage.DamageSource;

public class DamageSourceWrapper {



    public static DamageSourceWrapper of(DamageSource source) {
        if (source == null) return null;

        return new DamageSourceWrapper(source);
    }


    private final DamageSource source;

    private DamageSourceWrapper(DamageSource source) {
        this.source = source;
    }

    public DamageSource getSource() {
        return source;
    }

    public boolean bypassesArmor() {
        //#if MC >= 11904
        return source.isIn(net.minecraft.registry.tag.DamageTypeTags.BYPASSES_ARMOR);
        //#else
        //$$ return source.bypassesArmor();
        //#endif
    }

    public boolean bypassesShield() {
        //#if MC >= 11904
        return source.isIn(net.minecraft.registry.tag.DamageTypeTags.BYPASSES_SHIELD);
        //#else
        //$$ return source.isUnblockable();
        //#endif
    }

    public boolean isOutOfWorld() {
        //#if MC >= 11904
        return source.isOf(net.minecraft.entity.damage.DamageTypes.OUT_OF_WORLD);
        //#else
        //$$ return source.isOutOfWorld();
        //#endif
    }

}
