package adris.altoclef.util;

import net.minecraft.item.Item;

import java.util.Objects;

public class SmeltTarget {

    private final ItemTarget item;
    private final Item[] optionalMaterials;
    private ItemTarget material;

    public SmeltTarget(ItemTarget item, ItemTarget material, Item... optionalMaterials) {
        this.item = item;
        this.material = material;
        this.material = new ItemTarget(material, this.item.getTargetCount());
        this.optionalMaterials = optionalMaterials;
    }

    public ItemTarget getItem() {
        return item;
    }

    public ItemTarget getMaterial() {
        return material;
    }

    public Item[] getOptionalMaterials() {
        return optionalMaterials;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SmeltTarget that = (SmeltTarget) o;
        return Objects.equals(material, that.material) && Objects.equals(item, that.item);
    }

    @Override
    public int hashCode() {
        return Objects.hash(material, item);
    }
}
