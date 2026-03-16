package adris.altoclef.multiversion;

//#if MC >= 12005
import net.minecraft.component.type.FoodComponent;
//#else
//$$ import net.minecraft.item.FoodComponent;
//#endif


public class FoodComponentWrapper {


    public static FoodComponentWrapper of(FoodComponent component) {
        if (component == null) return null;

        return new FoodComponentWrapper(component);
    }

    private final FoodComponent component;

    private FoodComponentWrapper(FoodComponent component) {
        this.component = component;
    }

    public int getHunger() {
        //#if MC >= 12005
        return component.nutrition();
        //#else
        //$$ return component.getHunger();
        //#endif
    }

    public float getSaturationModifier() {
        //#if MC >= 12005
        return component.saturation();
        //#else
        //$$ return component.getSaturationModifier();
        //#endif
    }
}
