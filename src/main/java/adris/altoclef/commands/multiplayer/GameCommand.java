package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.ChoiceArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.multiplayer.minigames.*;
import adris.altoclef.tasks.speedrun.beatgame.BeatMinecraftTask;
import adris.altoclef.util.agent.Pipeline;
import net.minecraft.util.math.BlockPos;

public class GameCommand extends Command {
    public GameCommand() {
        super("game", "Run the main game or minigame pipeline (task chain)",
                new ChoiceArg("pipeline", "", java.util.List.of(
                        "none", "sw", "swt", "skywars", "mm", "murder",
                        "bw", "bedwars", "kpvp", "kitpvp", "thepit", "pit",
                        "skypvp", "megabattle", "speedrun")));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String pipelineStr = parser.get(String.class);
        if (pipelineStr == null || pipelineStr.isBlank()) {
            Debug.logMessage("Pipeline set to None");
            AltoClef.setPipeline(Pipeline.None);
            finish();
            return;
        }
        pipelineStr = pipelineStr.toLowerCase();

        switch (pipelineStr) {
            case "none", "no":
                Debug.logMessage("Pipeline set to None");
                AltoClef.setPipeline(Pipeline.None);
                finish();
                break;
            case "swt", "skywarsteam", "sky_wars_team":
                AltoClef.setPipeline(Pipeline.SkyWars);
                Debug.logMessage("Pipeline set to swt");
                mod.getButler().AddNearestPlayerToFriends(mod, 15);
                mod.runUserTask(new SkyWarsTask(new BlockPos(0, 0, 0), 0d, false), this::finish);
                break;
            case "sw", "skywars", "sky_wars":
                AltoClef.setPipeline(Pipeline.SkyWars);
                Debug.logMessage("Pipeline set to sw");
                mod.runUserTask(new SkyWarsTask(new BlockPos(0, 0, 0), 0d, false), this::finish);
                break;
            case "mm", "murder", "murdermystery", "mystery":
                AltoClef.setPipeline(Pipeline.MurderMystery);
                Debug.logMessage("Pipeline set to mm");
                mod.runUserTask(new MurderMysteryTask(-1), this::finish);
                break;
            case "bw", "bed", "bedwars":
                AltoClef.setPipeline(Pipeline.BedWars);
                Debug.logMessage("Pipeline set to bw");
                mod.runUserTask(new BedWarsTask(), this::finish);
                break;
            case "kpvp", "kitpvp", "kit":
                Debug.logMessage("Pipeline set to KitPVP");
                mod.runUserTask(new KitPVPTask(mod.getPlayer().getBlockPos(), 900, false), this::finish);
                break;
            case "thepit", "pit":
                Debug.logMessage("Pipeline set to ThePit");
                mod.runUserTask(new SkyWarsTask(mod.getPlayer().getBlockPos(), true, false), this::finish);
                break;
            case "skypvp", "sky_pvp":
                Debug.logMessage("Pipeline set to SkyPvP (MineLegacy)");
                mod.runUserTask(new SkyPvpTask(), this::finish);
                break;
            case "megabattle", "mega", "evil", "yandere":
                AltoClef.setPipeline(Pipeline.BattleRoyale);
                Debug.logMessage("Pipeline set yandere");
                finish();
                break;
            default:
                // Speedrun / beat Minecraft
                Debug.logMessage("Pipeline set to SpeedRun");
                AltoClef.setPipeline(Pipeline.SpeedRun);
                mod.runUserTask(new BeatMinecraftTask(mod), this::finish);
                break;
        }
    }
}
