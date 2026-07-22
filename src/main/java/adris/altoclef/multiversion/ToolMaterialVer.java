package adris.altoclef.multiversion;

//#if MC < 12111
import net.minecraft.item.ToolItem;
//#endif
import net.minecraft.item.ToolMaterial;
//#if MC < 12111
import net.minecraft.item.ToolMaterials;
//#endif

public class ToolMaterialVer {

    //#if MC < 12111
    public static int getMiningLevel(ToolItem item) {
        return getMiningLevel(item.getMaterial());
    }
    //#endif

    public static int getMiningLevel(ToolMaterial material) {
        //#if MC < 12111
        if (material.equals(ToolMaterials.WOOD) || material.equals(ToolMaterials.GOLD)) {
            return 0;
        } else if (material.equals(ToolMaterials.STONE)) {
            return 1;
        } else if (material.equals(ToolMaterials.IRON)) {
            return 2;
        } else if (material.equals(ToolMaterials.DIAMOND)) {
            return 3;
        } else if (material.equals(ToolMaterials.NETHERITE)) {
            return 4;
        }
        throw new IllegalStateException("Unexpected value: " + material);
        //#else
        //$$ // TODO [1.21.11] ToolMaterials enum deleted — constants moved to ToolMaterial class
        //$$ throw new UnsupportedOperationException("Not yet implemented for 1.21.11");
        //#endif
    }

}
