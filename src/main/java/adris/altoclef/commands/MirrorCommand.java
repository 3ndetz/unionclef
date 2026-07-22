package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.multiplayer.MirrorTask;

/**
 * @mirror — record a player's movement and replay it (the bot reproduces their path + jumps).
 *   @mirror <ник>        record ~8s of the player, then replay it from where you stand
 *   @mirror <ник> rec    only record (replay later with @mirror play)
 *   @mirror <ник> <sec>  record <sec> seconds, then replay
 *   @mirror play         replay the last recording from your current spot
 * Stop anytime with @stop / @idle.
 */
public class MirrorCommand extends Command {
    public MirrorCommand() {
        super("mirror", "Record + replay a player's movement (path + jumps). @mirror <ник> [rec|<sec>] | @mirror play",
                new StringArg("player", ""),
                new StringArg("mode", "8"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String player = parser.get(String.class);
        String mode = parser.get(String.class);
        if (player == null || player.isEmpty()) {
            finish();
            return;
        }
        if (player.equalsIgnoreCase("play") || player.equalsIgnoreCase("replay")) {
            mod.runUserTask(new MirrorTask("", MirrorTask.Mode.REPLAY, 8), this::finish);
            return;
        }
        MirrorTask.Mode m = MirrorTask.Mode.RECORD_THEN_REPLAY;
        double secs = 8;
        if (mode != null && !mode.isEmpty()) {
            if (mode.equalsIgnoreCase("rec") || mode.equalsIgnoreCase("record")) {
                m = MirrorTask.Mode.RECORD;
            } else if (mode.equalsIgnoreCase("play") || mode.equalsIgnoreCase("replay")) {
                m = MirrorTask.Mode.REPLAY;
            } else {
                try { secs = Double.parseDouble(mode); } catch (NumberFormatException ignored) { }
            }
        }
        mod.runUserTask(new MirrorTask(player, m, secs), this::finish);
    }
}
