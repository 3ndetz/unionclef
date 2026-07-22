package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.ui.MessagePriority;

public class NickCommand extends Command {
    public NickCommand() {
        super("nick", "Set new nickname (applies after rejoin)", new StringArg("name"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String name = parser.get(String.class);
        // Swap the OFFLINE client session username directly (applies on the NEXT connect). The
        // server-side /nick is region-blocked on the musteryworld network ("не разрешена в этом
        // регионе"), so it never changed the auth nick — changePlayerName is what actually works.
        boolean ok = AltoClef.changePlayerName(name);
        mod.log(ok ? ("Nick set to '" + name + "' — reconnect to the server to apply it")
                   : ("Nick change FAILED for '" + name + "'"));
        finish();
    }
}
