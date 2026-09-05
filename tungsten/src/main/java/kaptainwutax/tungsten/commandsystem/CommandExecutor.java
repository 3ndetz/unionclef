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

    // ⛔ DEAD CODE (this one method only), CONFIRMED 2026-09-05: this specific overload has no
    // callers -- grepped the tree; TungstenMod.getCommandExecutor() itself IS live
    // (allCommands()/registerNewCommand() are used from TungstenCommands.java and several
    // mixins), but the actual chained-command chat handler
    // (MixinClientPlayNetworkHandler.onSendChatMessage) calls executeRecursive() directly, never
    // this wrapper. CORRECTION to an earlier pass of this same fix: the comment below ("separated
    // by ;") is itself stale/wrong -- confirmed by reading the LIVE chained-command path, which
    // unambiguously uses "|" as the divider (MixinClientPlayNetworkHandler checks
    // `message.contains("|")`; MixinSuggestionWindow and StringProcessorHelper.findClosestCharIndex
    // both key off the literal '|' character). Matched this dead method's separator to that
    // convention rather than to its own comment, so it doesn't diverge from the rest of the
    // feature if it's ever wired up.
    public void execute(String line, Runnable onFinish, Consumer<CommandException> getException) {
        if (!isClientCommand(line)) return;
        line = line.substring(getCommandPrefix().length());
        // Run commands separated by |
        // ⛔ FIXED 2026-09-05: was `line.split("|")` -- "|" is a regex alternation between two
        // empty patterns, which matches a zero-width string at every position, so this split
        // between EVERY CHARACTER instead of on the literal pipe. `"a|b".split("|")` returns
        // {"a", "|", "b"} (three single-character strings), not {"a", "b"}; the escaped form does
        // the right thing.
        String[] parts = line.split("\\|");
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
