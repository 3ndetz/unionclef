package adris.altoclef.mixins;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PersistentProjectileEntity.class)
public interface PersistentProjectileEntityAccessor {
    // ⛔ FIXED 2026-09-05: was `//#if MC < 12111 @Accessor("inGround") boolean isInGround(); //#endif`
    // -- compiled to nothing on MC >= 12111, so every caller's `((PersistentProjectileEntityAccessor)
    // entity).isInGround()` silently read `false` unconditionally (documented as an OPEN finding,
    // ALTOCLEF-MOBDEFENSE-CHAINS-TRACKERS-OPEN-2026-09-05, since this session had no compiler to
    // find the right replacement). Disassembled the actual Yarn-mapped 1.21.11
    // PersistentProjectileEntity.class from the local Loom cache (.gradle/loom-cache/minecraftMaven/
    // .../minecraft-merged-...-1.21.11-...-v2.jar) with `javap` -- read-only bytecode inspection, no
    // compile/build/Gradle run -- and confirmed `isInGround()`/`setInGround(boolean)` still exist on
    // 1.21.11, unchanged in name, just backed by a DataTracker slot (TrackedData<Boolean> IN_GROUND)
    // instead of a plain field, and declared `protected` rather than package-private. `@Accessor`
    // targets a FIELD (there is no plain `inGround` field any more); `@Invoker` targets a METHOD,
    // which is what's needed now. Kept the interface method's own name identical to the old branch
    // (`isInGround()`) so no call site has to change -- only the annotation and its import do.
    //#if MC < 12111
    @Accessor("inGround")
    boolean isInGround();
    //#else
    //$$ @Invoker("isInGround")
    //$$ boolean isInGround();
    //#endif
}
