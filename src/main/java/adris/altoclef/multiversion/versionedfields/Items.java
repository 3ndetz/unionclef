package adris.altoclef.multiversion.versionedfields;


import net.minecraft.item.Item;

/**
 * A helper class implementing items that are not yet supported in certain versions
 * Using these in non-supported versions might lead to strange bugs and/or crashes...
 * Please see {@link VersionedFieldHelper#isSupported(Object)}
 */
public class Items extends net.minecraft.item.Items {


    public static final Item UNSUPPORTED = VersionedFieldHelper.createUnsafeUnsupportedItem();

    //#if MC <= 11802
    //$$ public static final Item MANGROVE_PROPAGULE = UNSUPPORTED;
    //$$ public static final Item CHERRY_SAPLING = UNSUPPORTED;
    //$$ public static final Item SCULK_CATALYST = UNSUPPORTED;
    //$$ public static final Item MANGROVE_PLANKS = UNSUPPORTED;
    //$$ public static final Item CHERRY_PLANKS = UNSUPPORTED;
    //$$ public static final Item BAMBOO_PLANKS = UNSUPPORTED;
    //$$ public static final Item MANGROVE_LEAVES = UNSUPPORTED;
    //$$ public static final Item CHERRY_LEAVES = UNSUPPORTED;
    //$$ public static final Item MANGROVE_WOOD = UNSUPPORTED;
    //$$ public static final Item MANGROVE_BUTTON = UNSUPPORTED;
    //$$ public static final Item BAMBOO_BUTTON = UNSUPPORTED;
    //$$ public static final Item CHERRY_BUTTON = UNSUPPORTED;
    //$$ public static final Item MANGROVE_SIGN = UNSUPPORTED;
    //$$ public static final Item BAMBOO_SIGN = UNSUPPORTED;
    //$$ public static final Item CHERRY_SIGN = UNSUPPORTED;
    //$$ public static final Item ACACIA_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item BIRCH_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item CRIMSON_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item DARK_OAK_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item OAK_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item JUNGLE_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item SPRUCE_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item WARPED_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item MANGROVE_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item BAMBOO_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item CHERRY_HANGING_SIGN = UNSUPPORTED;
    //$$ public static final Item MANGROVE_PRESSURE_PLATE = UNSUPPORTED;
    //$$ public static final Item BAMBOO_PRESSURE_PLATE = UNSUPPORTED;
    //$$ public static final Item CHERRY_PRESSURE_PLATE = UNSUPPORTED;
    //$$ public static final Item MANGROVE_FENCE = UNSUPPORTED;
    //$$ public static final Item BAMBOO_FENCE = UNSUPPORTED;
    //$$ public static final Item CHERRY_FENCE = UNSUPPORTED;
    //$$ public static final Item MANGROVE_FENCE_GATE = UNSUPPORTED;
    //$$ public static final Item BAMBOO_FENCE_GATE = UNSUPPORTED;
    //$$ public static final Item CHERRY_FENCE_GATE = UNSUPPORTED;
    //$$ public static final Item MANGROVE_BOAT = UNSUPPORTED;
    //$$ public static final Item CHERRY_BOAT = UNSUPPORTED;
    //$$ public static final Item MANGROVE_DOOR = UNSUPPORTED;
    //$$ public static final Item BAMBOO_DOOR = UNSUPPORTED;
    //$$ public static final Item CHERRY_DOOR = UNSUPPORTED;
    //$$ public static final Item MANGROVE_SLAB = UNSUPPORTED;
    //$$ public static final Item BAMBOO_SLAB = UNSUPPORTED;
    //$$ public static final Item CHERRY_SLAB = UNSUPPORTED;
    //$$ public static final Item MANGROVE_STAIRS = UNSUPPORTED;
    //$$ public static final Item BAMBOO_STAIRS = UNSUPPORTED;
    //$$ public static final Item CHERRY_STAIRS = UNSUPPORTED;
    //$$ public static final Item MANGROVE_TRAPDOOR = UNSUPPORTED;
    //$$ public static final Item BAMBOO_TRAPDOOR = UNSUPPORTED;
    //$$ public static final Item CHERRY_TRAPDOOR = UNSUPPORTED;
    //$$ public static final Item MANGROVE_LOG = UNSUPPORTED;
    //$$ public static final Item STRIPPED_MANGROVE_LOG = UNSUPPORTED;
    //$$ public static final Item STRIPPED_MANGROVE_WOOD = UNSUPPORTED;
    //$$ public static final Item CHERRY_LOG = UNSUPPORTED;
    //$$ public static final Item CHERRY_WOOD = UNSUPPORTED;
    //$$ public static final Item STRIPPED_CHERRY_LOG = UNSUPPORTED;
    //$$ public static final Item STRIPPED_CHERRY_WOOD = UNSUPPORTED;
    //$$ public static final Item BAMBOO_RAFT = UNSUPPORTED;
    //$$ public static final Item CHISELED_BOOKSHELF = UNSUPPORTED;
    //$$ public static final Item DECORATED_POT = UNSUPPORTED;
    //$$ public static final Item BRUSH = UNSUPPORTED;
    //$$ public static final Item BAMBOO_BLOCK = UNSUPPORTED;
    //$$ public static final Item NETHERITE_UPGRADE_SMITHING_TEMPLATE = UNSUPPORTED;
    //$$ public static final Item STRIPPED_BAMBOO_BLOCK = UNSUPPORTED;
    //$$ public static final Item TORCHFLOWER_SEEDS = UNSUPPORTED;
    //$$ public static final Item PINK_PETALS = UNSUPPORTED;
    //$$ public static final Item MANGROVE_ROOTS = UNSUPPORTED;
    //$$ public static final Item MUDDY_MANGROVE_ROOTS = UNSUPPORTED;
    //$$ public static final Item MUD = UNSUPPORTED;
    //#endif

    //#if MC <=11605
    //$$ public static final Item COBBLED_DEEPSLATE = UNSUPPORTED;
    //$$ public static final Item CALCITE = UNSUPPORTED;
    //$$ public static final Item TUFF = UNSUPPORTED;
    //$$ public static final Item RAW_IRON = UNSUPPORTED;
    //$$ public static final Item RAW_GOLD = UNSUPPORTED;
    //$$ public static final Item RAW_COPPER = UNSUPPORTED;
    //$$ public static final Item AMETHYST_SHARD = UNSUPPORTED;
    //$$ public static final Item POINTED_DRIPSTONE = UNSUPPORTED;
    //$$ public static final Item AMETHYST_BLOCK = UNSUPPORTED;
    //$$ public static final Item DRIPSTONE_BLOCK = UNSUPPORTED;
    //$$ public static final Item COPPER_INGOT = UNSUPPORTED;
    //$$ public static final Item ROOTED_DIRT = UNSUPPORTED;
    //$$ public static final Item GLOW_INK_SAC = UNSUPPORTED;
    //$$ public static final Item GLOW_LICHEN = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE = UNSUPPORTED;
    //$$ public static final Item SMOOTH_BASALT = UNSUPPORTED;
    //$$ public static final Item COPPER_ORE = UNSUPPORTED;
    //$$ public static final Item COPPER_BLOCK = UNSUPPORTED;
    //$$ public static final Item RAW_IRON_BLOCK = UNSUPPORTED;
    //$$ public static final Item RAW_GOLD_BLOCK = UNSUPPORTED;
    //$$ public static final Item RAW_COPPER_BLOCK = UNSUPPORTED;
    //$$ public static final Item CUT_COPPER_SLAB = UNSUPPORTED;
    //$$ public static final Item CUT_COPPER_STAIRS = UNSUPPORTED;
    //$$ public static final Item COBBLED_DEEPSLATE_SLAB = UNSUPPORTED;
    //$$ public static final Item COBBLED_DEEPSLATE_STAIRS = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE_WALL = UNSUPPORTED;
    //$$ public static final Item POLISHED_DEEPSLATE_SLAB = UNSUPPORTED;
    //$$ public static final Item POLISHED_DEEPSLATE_STAIRS = UNSUPPORTED;
    //$$ public static final Item POLISHED_DEEPSLATE_WALL = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE_BRICK_SLAB = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE_BRICK_STAIRS = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE_BRICK_WALL = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE_TILE_SLAB = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE_TILE_STAIRS = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE_TILE_WALL = UNSUPPORTED;
    //$$ public static final Item CHISELED_DEEPSLATE = UNSUPPORTED;
    //$$ public static final Item MOSS_BLOCK = UNSUPPORTED;
    //$$ public static final Item MOSS_CARPET = UNSUPPORTED;
    //$$ public static final Item AMETHYST_CLUSTER = UNSUPPORTED;
    //$$ public static final Item BUDDING_AMETHYST = UNSUPPORTED;
    //$$ public static final Item FLOWERING_AZALEA = UNSUPPORTED;
    //$$ public static final Item AXOLOTL_BUCKET = UNSUPPORTED;
    //$$ public static final Item POWDER_SNOW_BUCKET = UNSUPPORTED;
    //$$ public static final Item POLISHED_DEEPSLATE = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE_BRICKS = UNSUPPORTED;
    //$$ public static final Item DEEPSLATE_TILES = UNSUPPORTED;
    //$$ public static final Item CUT_COPPER = UNSUPPORTED;
    //$$ public static final Item CRACKED_DEEPSLATE_BRICKS = UNSUPPORTED;
    //$$ public static final Item CRACKED_DEEPSLATE_TILES = UNSUPPORTED;
    //$$ public static final Item SPYGLASS = UNSUPPORTED;
    //$$ public static final Item CANDLE = UNSUPPORTED;
    //$$ public static final Item LIGHTNING_ROD = UNSUPPORTED;
    //$$ public static final Item TINTED_GLASS = UNSUPPORTED;
    //$$ public static final Item GLOW_ITEM_FRAME = UNSUPPORTED;
    //#endif

}
