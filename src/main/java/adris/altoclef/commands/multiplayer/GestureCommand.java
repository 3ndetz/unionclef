package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.PlayerArg;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.RuntimeCommandException;
import adris.altoclef.tasks.multiplayer.GestureTask;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

public class GestureCommand extends Command {
    public GestureCommand() {
        super("gesture", "Show gesture to someone",
                new PlayerArg("username", null),
                new StringArg("gesture", "Hey"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String username = parser.get(String.class);
        if (username == null) {
            finish();
            return;
        }

        Entity entity = getValidPlayer(mod, username);

        String gestureStr = parser.get(String.class);
        GestureTask.Gesture gesture = GestureTask.Gesture.Hey;
        if (gestureStr != null) {
            try {
                gesture = GestureTask.Gesture.valueOf(gestureStr);
            } catch (IllegalArgumentException e) {
                Debug.logWarning("GestureCommand: Invalid gesture: " + gestureStr);
            }
        }
        GestureTask.Gesture finalGesture = gesture;
        mod.runUserTask(new GestureTask(entity, finalGesture), this::finish);
    }

    public static Optional<Entity> getPlayerTarget(AltoClef mod, String username) {
        if (mod.getEntityTracker().isPlayerLoaded(username)) {
            return mod.getEntityTracker().getPlayerEntity(username).map(Entity.class::cast);
        }
        return Optional.empty();
    }

    public static Entity getValidPlayer(AltoClef mod, String username) throws CommandException {
        if (mod.getEntityTracker().isPlayerLoaded(username)) {
            Optional<Entity> player = mod.getEntityTracker().getPlayerEntity(username).map(Entity.class::cast);
            if (player.isPresent()) {
                return player.get();
            } else {
                Optional<Vec3d> pos = mod.getEntityTracker().getPlayerMostRecentPosition(username);
                if (pos.isPresent()) {
                    throw new RuntimeCommandException("Player " + username
                            + " is not in view now, but last time was at " + pos.get());
                } else {
                    throw new RuntimeCommandException("Player " + username + " is unreachable: too far or not visible.");
                }
            }
        }
        throw new RuntimeCommandException("Player " + username + " never appeared in the game.");
    }
}
