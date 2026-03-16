package adris.altoclef.multiversion.versionedfields;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.world.World;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

@SuppressWarnings("restriction")
public class VersionedFieldHelper {

    private static final Unsafe unsafe;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isSupported(Object obj) {
        if (obj == null) return true;
        return !(obj instanceof UnsupportedBlock) && !(obj instanceof UnsupportedItem) && !(obj instanceof UnsupportedEntity);
    }


    protected static UnsupportedBlock createUnsafeUnsupportedBlock() {
        try {
            // Instantiate the subclass without calling the constructor
            return (UnsupportedBlock) unsafe.allocateInstance(UnsupportedBlock.class);
        } catch (InstantiationException e) {
            e.printStackTrace();
            return null;
        }
    }


    protected static class UnsupportedBlock extends Block {
        public UnsupportedBlock(Settings settings) {
            super(settings);
            throw new IllegalStateException("Unsupported!");
        }
    }

    protected static UnsupportedItem createUnsafeUnsupportedItem() {
        try {
            // Instantiate the subclass without calling the constructor
            return (UnsupportedItem) unsafe.allocateInstance(UnsupportedItem.class);
        } catch (InstantiationException e) {
            e.printStackTrace();
            return null;
        }
    }

    protected static class UnsupportedItem extends Item {
        public UnsupportedItem(Settings settings) {
            super(settings);
            throw new IllegalStateException("Unsupported!");
        }
    }


    protected static Class<? extends Entity> getUnsupportedEntityClass() {
        return UnsupportedEntity.class;
    }

    protected static abstract class UnsupportedEntity extends Entity {
        public UnsupportedEntity(EntityType<?> type, World world) {
            super(type, world);
            throw new IllegalStateException("Unsupported!");
        }
    }

}
