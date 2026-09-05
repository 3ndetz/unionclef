package kaptainwutax.tungsten.commandsystem;

import java.util.Collection;
import java.util.HashMap;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;

public class CommandExecutor {

	public static final CommandDispatcher<CommandSource> DISPATCHER = new CommandDispatcher<>();
    private final HashMap<String, Command> _commandSheet = new HashMap<>();
    private final TungstenMod _mod;

    public CommandExecutor(TungstenMod mod) {
        _mod = mod;
    }

    public void registerNewCommand(Command... commands) {
        for (Command command : commands) {
            if (_commandSheet.containsKey(command.getName())) {
            	TungstenMod.LOG.info("Command with name " + command.getName() + " already exists! Can't register that name twice.");
                continue;
            }
            command.registerTo(DISPATCHER);
            _commandSheet.put(command.getName(), command);
        }
    }

    private String getCommandPrefix() {
        return TungstenMod.getCommandPrefix();
    }

    public boolean isClientCommand(String line) {
        return line.startsWith(getCommandPrefix());
    }

    // This is how we "nest" command finishes so we can complete them in order.
    public void executeRecursive(Command[] commands, String[] parts, int index, Runnable onFinish, Consumer<CommandException> getException) {
        if (index >= commands.length) {
            onFinish.run();
            return;
        }
        Command command = commands[index];
        String part = parts[index];
        try {
            if (command == null) {
                getException.accept(new CommandException("Invalid command:" + part));
                executeRecursive(commands, parts, index + 1, onFinish, getException);
            } else {
                command.run(_mod, part.contains("@") ? part.split("@")[1] : part, () -> executeRecursive(commands, parts, index + 1, onFinish, getException));
            }
        } catch (CommandException ae) {
//            getException.accept(new CommandException(ae.getMessage() + "\nUsage: " + command.getHelpRepresentation(), ae));
        }
    }

    // ⛔ DEAD CODE, CONFIRMED 2026-09-05: nothing calls TungstenMod.getCommandExecutor() anywhere
    // in the tree (grepped), so this method, executeRecursive(), getCommand() and
    // isClientCommand() below never run. The real command path is entirely the static
    // DISPATCHER/dispatch() further down, invoked from the mixins. Left in place rather than
    // deleted (unlike this session's other dead-code removals) because this looks like an
    // unfinished multi-command-chaining feature, not a superseded duplicate of something live --
    // but the split("|") bug just below was fixed anyway so it doesn't bite whoever wires this up.
    public void execute(String line, Runnable onFinish, Consumer<CommandException> getException) {
        if (!isClientCommand(line)) return;
        line = line.substring(getCommandPrefix().length());
        // Run commands separated by ;
        // ⛔ FIXED 2026-09-05: was `line.split("|")` -- "|" is a regex alternation between two
        // empty patterns, which matches a zero-width string at every position, so this split
        // between EVERY CHARACTER instead of on ";" as the comment above says. `"a;b".split("|")`
        // returns {"a", ";", "b"} (three single-character strings), not {"a", "b"}.
        String[] parts = line.split(";");
        Command[] commands = new Command[parts.length];
        try {
            for (int i = 0; i < parts.length; ++i) {
                commands[i] = getCommand(parts[i]);
            }
        } catch (CommandException e) {
            getException.accept(e);
        }
        executeRecursive(commands, parts, 0, onFinish, getException);
    }
    
    
    
    public static void dispatch(String message) throws CommandSyntaxException {
        if (TungstenMod.mc == null || TungstenMod.mc.getNetworkHandler() == null) return;
        DISPATCHER.execute(message, TungstenMod.mc.getNetworkHandler().getCommandSource());
    }

    public void execute(String line, Consumer<CommandException> getException) {
        execute(line, () -> {
        }, getException);
    }

    public void execute(String line) {
        execute(line, ex -> Debug.logWarning(ex.getMessage()));
    }

    public void executeWithPrefix(String line) {
        if (!line.startsWith(getCommandPrefix())) {
            line = getCommandPrefix() + line;
        }
        execute(line);
    }

    private Command getCommand(String line) throws CommandException {
        line = line.trim();
        if (line.length() != 0) {
            String command = line;
            int firstSpace = line.indexOf(' ');
            if (firstSpace != -1) {
                command = line.substring(0, firstSpace);
            }

            if (!_commandSheet.containsKey(command)) {
                throw new CommandException("Command " + command + " does not exist.");
            }

            return _commandSheet.get(command);
        }
        return null;

    }

    public Collection<Command> allCommands() {
        return _commandSheet.values();
    }

    public Command get(String name) {
        return (_commandSheet.getOrDefault(name, null));
    }
}
