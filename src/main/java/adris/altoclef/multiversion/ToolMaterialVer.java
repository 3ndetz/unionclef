package adris.altoclef.multiversion;

import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.item.ToolMaterials;

public class ToolMaterialVer {

    public static int getMiningLevel(ToolItem item) {
        return getMiningLevel(item.getMaterial());
    }

    public static int getMiningLevel(ToolMaterial material) {
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
    }

}
