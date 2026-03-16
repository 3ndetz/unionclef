package adris.altoclef.tasks.speedrun.beatgame;

import adris.altoclef.multiversion.versionedfields.Items;
import adris.altoclef.tasks.speedrun.BeatMinecraftConfig;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.List;



public class UselessItems {

    //dont even pick up these items
    public final Item[] uselessItems;

    public UselessItems(BeatMinecraftConfig config)  {
        List<Item> uselessItemList = new ArrayList<>(List.of(
                //sapling
                Items.OAK_SAPLING,
                Items.SPRUCE_SAPLING,
                Items.BIRCH_SAPLING,
                Items.JUNGLE_SAPLING,
                Items.ACACIA_SAPLING,
                Items.DARK_OAK_SAPLING,
                Items.MANGROVE_PROPAGULE,
                Items.CHERRY_SAPLING,

                // seeds
                Items.BEETROOT_SEEDS,
                Items.MELON_SEEDS,
                Items.PUMPKIN_SEEDS,
                Items.WHEAT_SEEDS,
                Items.TORCHFLOWER_SEEDS,

                // random jung, might add more things in the future
                Items.FEATHER,
                Items.EGG,
                Items.PINK_PETALS,
                Items.BONE,
                Items.LEATHER,
                Items.RAW_COPPER,
                Items.WARPED_ROOTS,
                Items.GUNPOWDER,
                Items.MOSSY_COBBLESTONE,
                Items.SPRUCE_TRAPDOOR,
                Items.SANDSTONE_STAIRS,
                Items.STONE_BRICKS,
                Items.COARSE_DIRT,
                Items.SMOOTH_STONE,
                Items.FLOWER_POT,
                Items.MANGROVE_ROOTS,
                Items.POPPY,
                Items.MUDDY_MANGROVE_ROOTS,
                Items.SPIDER_EYE,
                Items.PINK_TULIP,

                Items.SPRUCE_STAIRS,
                Items.OAK_STAIRS,

                Items.LAPIS_LAZULI,
                Items.SUNFLOWER,
                Items.REDSTONE,
                Items.CRIMSON_ROOTS,
                Items.OAK_DOOR,

                Items.STRING,
                Items.WHITE_TERRACOTTA,
                Items.RED_TERRACOTTA,

                Items.MOSS_BLOCK,
                Items.MOSS_CARPET,
                Items.BOW,

                Items.EMERALD,
                Items.IRON_NUGGET,
                Items.SHORT_GRASS,
                Items.COBBLESTONE_WALL,
                Items.COBBLESTONE_STAIRS,
                Items.COBBLESTONE_SLAB,
                Items.CLAY_BALL,
                Items.DANDELION,
                Items.SUGAR_CANE,
                Items.CHEST,
                Items.RAIL,
                Items.CALCITE,
                Items.AMETHYST_BLOCK,
                Items.AMETHYST_CLUSTER,
                Items.AMETHYST_SHARD,
                Items.BUDDING_AMETHYST,
                Items.SMOOTH_BASALT,
                Items.AZURE_BLUET,

                Items.ACACIA_DOOR,
                Items.OAK_FENCE,
                Items.COMPOSTER,
                Items.OAK_PRESSURE_PLATE,
                Items.JUNGLE_DOOR,
                Items.CHISELED_SANDSTONE,
                Items.CACTUS,
                Items.MUD,
                Items.MANGROVE_LEAVES,
                Items.SMOOTH_SANDSTONE_SLAB,
                Items.SANDSTONE_WALL,
                Items.TNT,
                Items.PRISMARINE_CRYSTALS,
                Items.SNOWBALL,
                Items.DRIPSTONE_BLOCK,
                Items.POINTED_DRIPSTONE,
                Items.ARROW,
                Items.YELLOW_TERRACOTTA,
                Items.TUFF,
                Items.SPRUCE_STAIRS,
                Items.SPRUCE_DOOR,
                Items.SPRUCE_FENCE,
                Items.SPRUCE_FENCE_GATE,
                Items.ORANGE_TERRACOTTA,
                Items.HEART_OF_THE_SEA,
                Items.POTION,
                Items.FLOWERING_AZALEA,
                Items.COPPER_INGOT,
                Items.ACACIA_SLAB,
                Items.RABBIT_HIDE,
                Items.RABBIT_FOOT,

                // nether stuff
                Items.SOUL_SAND,
                Items.SOUL_SOIL,
                Items.NETHER_BRICK,
                Items.NETHER_BRICK_FENCE
        ));


        if (!config.barterPearlsInsteadOfEndermanHunt) {
            uselessItemList.add(Items.GOLD_NUGGET);
        }


        uselessItems = uselessItemList.toArray(new Item[0]);
    }

}
