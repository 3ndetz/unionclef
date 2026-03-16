package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.ChoiceArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.construction.PlaceSignTask;
import adris.altoclef.tasks.construction.compound.ConstructGraveTask;
import adris.altoclef.tasks.construction.compound.ConstructIronGolemTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;

import java.util.List;

/*
 * build <structure> [text / args]
 *
 * Examples:
 *   @build grave Hello world!
 *   @build sign Hello world!
 *   @build golem
 *   @build bed
 *   @build portal
 */
public class BuildCommand extends Command {

    private String _capturedArgs = null;

    public BuildCommand() {
        super("build", "Build a specific structure (grave/sign/golem/bed/portal)",
                new ChoiceArg("structure", List.of("grave", "sign", "golem", "bed", "portal")));
    }

    @Override
    public void run(AltoClef mod, String line, Runnable onFinish) throws CommandException {
        // Capture everything after "build " as raw args
        int spaceIdx = line.indexOf(' ');
        _capturedArgs = (spaceIdx >= 0) ? line.substring(spaceIdx + 1).trim() : "";
        super.run(mod, line, onFinish);
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String args = _capturedArgs != null ? _capturedArgs : "";
        if (args.isBlank()) {
            Debug.logWarning("No arguments provided! Usage: @build <grave|sign|golem|bed|portal> [text]");
            finish();
            return;
        }

        // Split into sub-command and optional remaining text
        int spaceIdx = args.indexOf(' ');
        String commandName = (spaceIdx >= 0 ? args.substring(0, spaceIdx) : args).toLowerCase();
        String text = (spaceIdx >= 0) ? args.substring(spaceIdx + 1).trim() : "";

        switch (commandName) {
            case "grave", "sign" -> {
                if (!text.isBlank()) {
                    Debug.logMessage("Constructing " + commandName + " with text: " + text);
                    if (commandName.equals("grave"))
                        mod.runUserTask(new ConstructGraveTask(text), this::finish);
                    else
                        mod.runUserTask(new PlaceSignTask(text), this::finish);
                } else {
                    Debug.logWarning("Text is blank!");
                    finish();
                }
            }
            case "golem" -> {
                Debug.logMessage("Constructing iron golem!");
                mod.runUserTask(new ConstructIronGolemTask(), this::finish);
            }
            case "bed" -> {
                Debug.logMessage("Constructing bed!");
                mod.runUserTask(new PlaceBedAndSetSpawnTask(), this::finish);
            }
            case "portal" -> {
                Debug.logMessage("Constructing portal!");
                mod.runUserTask(new ConstructNetherPortalBucketTask(), this::finish);
            }
            default -> {
                Debug.logWarning("Unknown structure: " + commandName);
                finish();
            }
        }
    }
}
