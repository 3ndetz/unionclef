package adris.altoclef;

import adris.altoclef.butler.ButlerConfig;
import adris.altoclef.butler.WhisperChecker;
import adris.altoclef.chains.GameMenuTaskChain;
import adris.altoclef.tasks.CraftGenericManuallyTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.construction.PlaceSignTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.construction.compound.ConstructGraveTask;
import adris.altoclef.tasks.construction.compound.ConstructIronGolemTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.container.StoreInAnyContainerTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.entity.ShiftEntityTask;
import adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask;
import adris.altoclef.tasks.examples.ExampleStrategyTask;
import adris.altoclef.tasks.examples.ExampleTask2;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.misc.RavageDesertTemplesTask;
import adris.altoclef.tasks.misc.RavageRuinedPortalsTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasks.multiplayer.LobbyTask;
import adris.altoclef.tasks.multiplayer.minigames.BattleRoyaleTask;
import adris.altoclef.tasks.multiplayer.minigames.KitPVPTask;
import adris.altoclef.tasks.multiplayer.minigames.MurderMysteryTask;
import adris.altoclef.tasks.multiplayer.minigames.SkyWarsTask;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.tasks.resources.CollectFlintTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask;
import adris.altoclef.tasks.speedrun.KillEnderDragonTask;
import adris.altoclef.tasks.speedrun.KillEnderDragonWithBedsTask;
import adris.altoclef.tasks.stupid.*;
import adris.altoclef.util.*;
import adris.altoclef.util.agent.AgentActionButtons;
import adris.altoclef.util.agent.AgentInputBridge;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.MapItemHelper;
import adris.altoclef.util.helpers.MouseMoveHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.chunk.EmptyChunk;

import java.io.*;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

import static adris.altoclef.util.helpers.StringHelper.removeMCFormatCodes;

/**
 * For testing.
 * <p>
 * As solonovamax suggested, this stuff should REALLY be moved to unit tests
 * https://github.com/adrisj7-AltoClef/altoclef/pull/7#discussion_r641792377
 * but getting timed tests and testing worlds set up in Minecraft might be
 * challenging, so this is the temporary resting place for garbage test code for now.
 */
@SuppressWarnings("EnhancedSwitchMigration")
public class Playground {

    public static void IDLE_TEST_INIT_FUNCTION(AltoClef mod) {
        // Test code here

        // Print all uncatalogued resources as well as resources that don't have a corresponding item
        /*
        Set<String> collectable = new HashSet<>(TaskCatalogue.resourceNames());
        Set<String> allItems = new HashSet<>();

        List<String> notCollected = new ArrayList<>();

        for (Identifier id : Registry.ITEM.getIds()) {
            Item item = Registry.ITEM.get(id);
            String name = ItemUtil.trimItemName(item.getTranslationKey());
            allItems.add(name);
            if (!collectable.contains(name)) {
                notCollected.add(name);
            }
        }

        List<String> notAnItem = new ArrayList<>();
        for (String cataloguedName : collectable) {
            if (!allItems.contains(cataloguedName)) {
                notAnItem.add(cataloguedName);
            }
        }

        notCollected.sort(String::compareTo);
        notAnItem.sort(String::compareTo);

        Function<List<String>, String> temp = (list) -> {
            StringBuilder result = new StringBuilder("");
            for (String name : list) {
                result.append(name).append("\n");
            }
            return result.toString();
        };

        Debug.logInternal("NOT COLLECTED YET:\n" + temp.apply(notCollected));
        Debug.logInternal("\n\n\n");
        Debug.logInternal("NOT ITEMS:\n" + temp.apply(notAnItem));
        */

        /* Print all catalogued resources

        List<String> resources = new ArrayList<>(TaskCatalogue.resourceNames());
        resources.sort(String::compareTo);
        StringBuilder result = new StringBuilder("ALL RESOURCES:\n");
        for (String name : resources) {
            result.append(name).append("\n");
        }
        Debug.logInternal("We got em:\n" + result.toString());

         */
    }

    public static void IDLE_TEST_TICK_FUNCTION(AltoClef mod) {
        // Test code here
    }

    public static void TEMP_TEST_FUNCTION(AltoClef mod, String arg) {
        //mod.runUserTask();
        Debug.logMessage("Running test...");
        String all_cases = "inv, stuckdebug, cb_reload, cb_stop, task_info, captmax, captdata, chatparsedebug, chatparseddebug_cancel, captcha_dataset, savemap, groundblock, cam 0/1/2, sign, sign2, pickup, chunk, structure, place, deadmeme, stacked, stacked2, ravage, temples, outer, smelt, iron, avoid, portal, kill, kill2, craft, food, temple, blaze, flint, unobtainable, piglin, stronghold, terminate, stoprot, startrot, t, tt, sw, swt, mm, kpvp, thepit, networktest, threats, lobby, strategy, shift, nearestinfo, chat, cmd, grave, mega, bow, mace, itemthreat, replace, bed, dragon, dragon-pearl, dragon-old, chest, 173, example, netherite, arrow, whisper, cursor, drop";
        // Parse sub-arg (for cases like "mm 1" or "shift 2")
        String[] argParts = arg.split(" ", 2);
        String subArg = argParts.length > 1 ? argParts[1] : "";

        switch (arg) {
            case "":
                // None specified
                Debug.logWarning("Please specify a test (ex. stacked, bed, terminate) or use help command @test help");
                break;
            case "help":
                Debug.logMessage(all_cases);
                break;
            case "inv":
                List<ItemStack> itemStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);
                for (ItemStack item : itemStacks) {
                    if (item.getItem() != null) {
                        String itemName = item.getItem().getName().getString().toLowerCase();
                        Debug.logMessage("item" + itemName);
                        if (!itemName.equals("воздух") && !itemName.equals("air")) {
                            if (item.contains(DataComponentTypes.CUSTOM_NAME)) {
                                String itemCustomName = removeMCFormatCodes(item.getName().getString().toLowerCase());
                                Debug.logMessage("ITEM CUSTOM NAME = " + itemCustomName);
                            }
                        }
                    }
                }
                Debug.logMessage("End of item list");
                break;
            case "stuckdebug":
                Debug.logMessage("STUCK DEBUG!!!!");
                mod.runUserTask(new GetToXZTask(0, 0));
                GameMenuTaskChain.StuckFixActivate();
                break;
            case "cb_reload":
                Debug.logMessage("PYTHON SENDER & CALLBACK RELOAD INITIATED");
                mod.reloadPythonSender();
                break;
            case "cb_stop":
                Debug.logMessage("PYTHON SENDER STOP!!!");
                mod.stopPythonSender();
                break;
            case "task_info":
                Debug.logMessage("INGAME INFO DICT:\n" + mod.getInfoSender().getTaskChainString());
                break;
            case "captmax":
                mod.getButler().CaptchaSolvingMode = "SOLVE_MAXIMUM";
                Debug.logMessage("capt set to SOLVE_MAXIMUM");
                break;
            case "captdata":
                mod.getButler().CaptchaSolvingMode = "GET_DATASET";
                Debug.logMessage("capt set to GET_DATASET");
                break;
            case "chatparsedebug":
                ButlerConfig.getInstance().debugChatParseResult = true;
                Debug.logMessage("set!");
                break;
            case "chatparseddebug_cancel":
                ButlerConfig.getInstance().debugChatParseResult = false;
                Debug.logMessage("unset!");
                break;
            case "captcha_dataset":
                File choDir = new File(MinecraftClient.getInstance().runDirectory, "map_screenshots");
                Debug.logMessage("DEBUG COMPARE" + adris.altoclef.util.ImageComparer.compareImage(new File(choDir, "cho1.png"), new File(choDir, "cho2.png")));
                break;
            case "savemap":
                Debug.logMessage("MAP SAVING INIT!!!");
                MapItemHelper.saveNonExistMapToDataset(mod);
                break;
            case "groundblock":
                Debug.logMessage("DEBUG BLOCKNAME = '" + mod.getInfoSender().getGroundBlock() + "'");
                Debug.logMessage("DEBUG HELDITEM = '" + mod.getInfoSender().getHeldItem() + "'");
                break;
            case "cam 0":
                Debug.logMessage("perspective 0");
                mod.getInfoSender().setPerspective(0);
                break;
            case "cam 1":
                Debug.logMessage("perspective 1");
                mod.getInfoSender().setPerspective(1);
                break;
            case "cam 2":
                Debug.logMessage("perspective 2");
                mod.getInfoSender().setPerspective(2);
                break;
            case "sign":
                mod.runUserTask(new PlaceSignTask("Hello there!"));
                break;
            case "sign2":
                mod.runUserTask(new PlaceSignTask(new BlockPos(10, 3, 10), "Hello there!"));
                break;
            case "pickup":
                mod.runUserTask(new PickupDroppedItemTask(new ItemTarget(Items.IRON_ORE, 3), true));
                break;
            case "chunk": {
                // We may have missed a chunk that's far away...
                BlockPos p = new BlockPos(100000, 3, 100000);
                Debug.logMessage("LOADED? " + (!(mod.getWorld().getChunk(p) instanceof EmptyChunk)));
                break;
            }
            case "structure":
                mod.runUserTask(new PlaceStructureBlockTask(new BlockPos(10, 6, 10)));
                break;
            case "place": {
                mod.runUserTask(new PlaceBlockNearbyTask(Blocks.CRAFTING_TABLE, Blocks.FURNACE));
                break;
            }
            case "deadmeme":
                File file = new File("test.txt");
                try {
                    FileReader reader = new FileReader(file);
                    mod.runUserTask(new BeeMovieTask("bruh", mod.getPlayer().getBlockPos(), reader));
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
                break;
            case "stacked":
                mod.runUserTask(new EquipArmorTask(Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET, Items.DIAMOND_BOOTS));
                break;
            case "stacked2":
                mod.runUserTask(new EquipArmorTask(Items.DIAMOND_CHESTPLATE));
                break;
            case "ravage":
                mod.runUserTask(new RavageRuinedPortalsTask());
                break;
            case "temples":
                mod.runUserTask(new RavageDesertTemplesTask());
                break;
            case "outer":
                mod.runUserTask(new GetToOuterEndIslandsTask());
                break;
            case "smelt":
                ItemTarget target = new ItemTarget("iron_ingot", 4);
                ItemTarget material = new ItemTarget("iron_ore", 4);
                mod.runUserTask(new SmeltInFurnaceTask(new SmeltTarget(target, material)));
                break;
            case "iron":
                mod.runUserTask(new ConstructIronGolemTask());
                break;
            case "avoid":
                mod.getBehaviour().avoidBlockBreaking((BlockPos b) -> (-1000 < b.getX() && b.getX() < 1000)
                        && (-1000 < b.getY() && b.getY() < 1000)
                        && (-1000 < b.getZ() && b.getZ() < 1000));
                Debug.logMessage("Testing avoid from -1000, -1000, -1000 to 1000, 1000, 1000");
                break;
            case "portal":
                mod.runUserTask(new EnterNetherPortalTask(new ConstructNetherPortalObsidianTask(), WorldHelper.getCurrentDimension() == Dimension.OVERWORLD ? Dimension.NETHER : Dimension.OVERWORLD));
                break;
            case "kill":
                List<ZombieEntity> zombs = mod.getEntityTracker().getTrackedEntities(ZombieEntity.class);
                if (zombs.size() == 0) {
                    Debug.logWarning("No zombs found.");
                } else {
                    LivingEntity entity = zombs.get(0);
                    mod.runUserTask(new KillEntityTask(entity));
                }
                break;
            case "kill2":
                mod.getMobDefenseChain().resetTargetEntity();
                mod.getMobDefenseChain().resetForceField();
                break;
            case "craft":
                // Test de-equip
                new Thread(() -> {
                    for (int i = 3; i > 0; --i) {
                        Debug.logMessage(i + "...");
                        sleepSec(1);
                    }

                    Item[] c = new Item[]{Items.COBBLESTONE};
                    Item[] s = new Item[]{Items.STICK};
                    CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("test pickaxe", new Item[][]{c, c, c, null, s, null, null, s, null}, 1);

                    mod.runUserTask(new CraftGenericManuallyTask(new RecipeTarget(Items.STONE_PICKAXE, 1, recipe)));
                }).start();
                break;
            case "food":
                mod.runUserTask(new CollectFoodTask(20));
                break;
            case "temple":
                mod.runUserTask(new LocateDesertTempleTask());
                break;
            case "blaze":
                mod.runUserTask(new CollectBlazeRodsTask(7));
                break;
            case "flint":
                mod.runUserTask(new CollectFlintTask(5));
                break;
            case "unobtainable":
                String fname = "unobtainables.txt";
                try {
                    int unobtainable = 0;
                    int total = 0;
                    File f = new File(fname);
                    FileWriter fw = new FileWriter(f);
                    for (Identifier id : Registries.ITEM.getIds()) {
                        Item item = Registries.ITEM.get(id);
                        if (!TaskCatalogue.isObtainable(item)) {
                            ++unobtainable;
                            fw.write(item.getTranslationKey() + "\n");
                        }
                        total++;
                    }
                    fw.flush();
                    fw.close();
                    Debug.logMessage(unobtainable + " / " + total + " unobtainable items. Wrote a list of items to \"" + f.getAbsolutePath() + "\".");
                } catch (IOException e) {
                    Debug.logWarning(e.toString());
                }
                break;
            case "piglin":
                mod.runUserTask(new TradeWithPiglinsTask(32, new ItemTarget(Items.ENDER_PEARL, 12)));
                break;
            case "stronghold":
                mod.runUserTask(new GoToStrongholdPortalTask(12));
                break;
            case "terminate":
                mod.runUserTask(new TerminatorTask(mod.getPlayer().getBlockPos(), 900));
                break;
            case "stoprot":
                MouseMoveHelper.RotationEnabled = false;
                break;
            case "startrot":
                MouseMoveHelper.RotationEnabled = true;
                break;
            case "t":
                mod.runUserTask(new SafeRandomShimmyTask());
                break;
            case "tt":
                break;
            case "sw":
                mod.runUserTask(new SkyWarsTask(mod.getPlayer().getBlockPos(), 300, false));
                break;
            case "swt":
                mod.getButler().ClearTeammates();
                mod.getButler().AddNearestPlayerToFriends(mod, 5);
                mod.runUserTask(new SkyWarsTask(mod.getPlayer().getBlockPos(), 300, false));
                break;
            case "mm": {
                mod.getInfoSender().UpdateServerInfo("serverMode", "murdermystery");
                int role_int = -1;
                try {
                    role_int = Integer.parseInt(subArg);
                } catch (Exception e) {
                    Debug.logWarning("Не указано значение, значит НЕИЗВЕСТНО");
                }
                mod.runUserTask(new MurderMysteryTask(role_int));
                break;
            }
            case "kpvp":
                mod.runUserTask(new KitPVPTask(mod.getPlayer().getBlockPos(), 900, false));
                break;
            case "thepit":
                mod.runUserTask(new SkyWarsTask(mod.getPlayer().getBlockPos(), true, false));
                break;
            case "networktest":
                Debug.logMessage("GLOBAL RECEIVERS" + ClientPlayNetworking.getGlobalReceivers() + " rc " + ClientPlayNetworking.getReceived());
                break;
            case "threats":
                Debug.logMessage(mod.getDamageTracker().getThreatStatus());
                break;
            case "lobby":
                Debug.logMessage("Run lobby task");
                mod.runUserTask(new LobbyTask());
                break;
            case "strategy":
                Debug.logMessage("Run strategy example task");
                mod.runUserTask(new ExampleStrategyTask());
                break;
            case "shift": {
                int shiftType = 0;
                ShiftEntityTask.ShiftType actualShiftType = ShiftEntityTask.ShiftType.values()[shiftType];
                try {
                    shiftType = Integer.parseInt(subArg);
                    actualShiftType = ShiftEntityTask.ShiftType.values()[shiftType];
                } catch (Exception e) {
                    Debug.logWarning("Не указано значение, значит НЕИЗВЕСТНО");
                }
                Optional<Entity> closestTarget = mod.getEntityTracker().getClosestEntity(
                        mod.getPlayer().getPos(),
                        entity -> true,
                        PlayerEntity.class, net.minecraft.entity.mob.MobEntity.class);
                if (closestTarget.isPresent()) {
                    Debug.logMessage("Testing shift task , ent:" + closestTarget.get().getName().getString() + ", type: " + actualShiftType.toString());
                    mod.runUserTask(new ShiftEntityTask(closestTarget.get(), actualShiftType));
                } else {
                    Debug.logWarning("No targets found.");
                }
                break;
            }
            case "nearestinfo":
                Debug.logMessage(mod.getInfoSender().nearestPlayersInfo(5, true));
                break;
            case "chat":
                mod.getMessageSender().sendChatInstant(subArg);
                break;
            case "cmd":
                mod.getMessageSender().sendCmdInstant(subArg);
                break;
            case "grave":
                mod.runUserTask(new ConstructGraveTask("Here lies a test."));
                break;
            case "mega":
                mod.runUserTask(new BattleRoyaleTask());
                break;
            case "bow": {
                List<PlayerEntity> players = mod.getEntityTracker().getTrackedEntities(PlayerEntity.class);
                if (players.isEmpty()) {
                    Debug.logWarning("No targets found.");
                    break;
                }
                PlayerEntity target_ply = players.get(0);
                mod.runUserTask(new ShootArrowSimpleProjectileTask(target_ply));
                break;
            }
            case "mace": {
                List<PlayerEntity> playerss = mod.getEntityTracker().getTrackedEntities(PlayerEntity.class);
                if (playerss.isEmpty()) {
                    Debug.logWarning("No targets found.");
                    break;
                }
                PlayerEntity target_plyy = playerss.getFirst();
                mod.runUserTask(new MacePunchTask(target_plyy, 8));
                break;
            }
            case "itemthreat": {
                List<PlayerEntity> players2 = mod.getEntityTracker().getTrackedEntities(PlayerEntity.class);
                if (players2.size() == 0) {
                    Debug.logWarning("No targets found.");
                    break;
                }
                PlayerEntity target_ply2 = players2.get(0);
                Debug.logMessage(target_ply2.getName().getString() + ": " + ItemHelper.getWeaponThreat(mod, target_ply2).toString());
                break;
            }
            case "replace": {
                BlockPos from = mod.getPlayer().getBlockPos().add(new Vec3i(-100, -20, -100));
                BlockPos to = mod.getPlayer().getBlockPos().add(new Vec3i(100, 255, 100));
                Block[] toFind = new Block[]{Blocks.GRASS_BLOCK};
                ItemTarget toReplace = new ItemTarget("crafting_table");
                mod.runUserTask(new ReplaceBlocksTask(toReplace, from, to, toFind));
                break;
            }
            case "bed":
                mod.runUserTask(new PlaceBedAndSetSpawnTask());
                break;
            case "dragon":
                mod.runUserTask(new KillEnderDragonWithBedsTask());
                break;
            case "dragon-pearl":
                mod.runUserTask(new ThrowEnderPearlSimpleProjectileTask(new BlockPos(0, 60, 0)));
                break;
            case "dragon-old":
                mod.runUserTask(new KillEnderDragonTask());
                break;
            case "chest":
                mod.runUserTask(new StoreInAnyContainerTask(true, new ItemTarget(Items.DIAMOND, 3)));
                break;
            case "173":
                mod.runUserTask(new SCP173Task());
                break;
            case "example":
                mod.runUserTask(new ExampleTask2());
                break;
            case "netherite":
                mod.runUserTask(TaskCatalogue.getSquashedItemTask(
                        new ItemTarget("netherite_pickaxe", 1),
                        new ItemTarget("netherite_sword", 1),
                        new ItemTarget("netherite_helmet", 1),
                        new ItemTarget("netherite_chestplate", 1),
                        new ItemTarget("netherite_leggings", 1),
                        new ItemTarget("netherite_boots", 1)));
                break;
            case "arrow": {
                List<GhastEntity> ghasts = mod.getEntityTracker().getTrackedEntities(GhastEntity.class);
                if (ghasts.size() == 0) {
                    Debug.logWarning("No ghasts found.");
                    break;
                }
                GhastEntity ghast = ghasts.get(0);
                mod.runUserTask(new ShootArrowSimpleProjectileTask(ghast));
                break;
            }
            case "whisper": {
                File check = new File("whisper.txt");
                try (FileInputStream fis = new FileInputStream(check);
                     Scanner sc = new Scanner(fis)) {
                    String me = sc.nextLine(),
                            template = sc.nextLine(),
                            message = sc.nextLine();
                    WhisperChecker.MessageResult result = WhisperChecker.tryParse(me, template, message);
                    Debug.logMessage("Got message: " + result);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                break;
            }
            case "cursor":
                Debug.logMessage("AgentInputActive is: " + AgentInputBridge.isAgentInputActive);
                AgentInputBridge.isAgentInputActive = !AgentInputBridge.isAgentInputActive;
                Debug.logMessage("AgentInputActive now: " + AgentInputBridge.isAgentInputActive);
                break;
            case "drop":
                AgentActionButtons.handleNativeKeyDropTest(mod);
                break;
            default:
                mod.logWarning("Test not found: \"" + arg + "\".");
                break;
        }
    }

    private static void sleepSec(double seconds) {
        try {
            Thread.sleep((int) (1000 * seconds));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


}
