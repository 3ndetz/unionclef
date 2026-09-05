package adris.altoclef.commandsystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.RuntimeCommandException;

import java.util.Collection;
import java.util.HashMap;
import java.util.function.Consumer;

public class CommandExecutor {

    private final HashMap<String, Command> commandSheet = new HashMap<>();
    private final AltoClef mod;

    public CommandExecutor(AltoClef mod) {
        this.mod = mod;
    }

    public void registerNewCommand(Command... commands) {
        for (Command command : commands) {
            for (String name : command.getNames()) {
                if (commandSheet.containsKey(name)) {
                    Debug.logInternal("Command with name " + name + " already exists! Can't register that name twice.");
                    continue;
                }
                
                commandSheet.put(name, command);
            }
        }
    }

    public String getCommandPrefix() {
        return mod.getModSettings().getCommandPrefix();
    }

    public boolean isClientCommand(String line) {
        return line.startsWith(getCommandPrefix());
    }

    // This is how we "nest" command finishes so we can complete them in order.
    private void executeRecursive(Command[] commands, String[] parts, int index, Runnable onFinish, Consumer<CommandException> getException) {
        if (index >= commands.length) {
            onFinish.run();
            return;
        }
        Command command = commands[index];
        String part = parts[index];
        try {
            if (command == null) {
                getException.accept(new RuntimeCommandException("Invalid command:" + part));
                executeRecursive(commands, parts, index + 1, onFinish, getException);
            } else {
                command.run(mod, part.strip(), () -> executeRecursive(commands, parts, index + 1, onFinish, getException));
            }
        } catch (CommandException ae) {
            try {
                getException.accept(new RuntimeCommandException(ae.getMessage() + "\nUsage: " + command.getHelpRepresentation(new StringReader(part).nextOrEmpty()), ae));
            } catch (RuntimeCommandException e) {
                throw new IllegalStateException("Should not happen!");
            }
        }
    }

    public void execute(String line, Runnable onFinish, Consumer<CommandException> getException) {
        if (!isClientCommand(line)) return;
        line = line.substring(getCommandPrefix().length());
        // Run commands separated by ;
        String[] parts = line.split(";");
        Command[] commands = new Command[parts.length];
        // ⛔ FIXED 2026-09-05: the try/catch used to wrap the WHOLE loop, so one bad command
        // anywhere in a ";"-chained list (e.g. ";cmd1;badcmd;cmd3") threw out of the loop entirely
        // on the first failure -- every part after the failing one was left as a never-checked
        // null in `commands[]`. executeRecursive() then reports EACH of those nulls as "Invalid
        // command: <part>" even though they were never looked up (cmd3 might have been perfectly
        // valid), on top of a duplicate error for the part that actually failed. Moved the
        // try/catch inside the loop so each part is resolved independently: one bad command
        // reports its own error and leaves only ITS OWN slot null, every other part (valid or
        // invalid on its own merits) still gets checked and, if valid, still runs.
        for (int i = 0; i < parts.length; ++i) {
            String part = parts[i].strip();
            if (part.startsWith(getCommandPrefix())) {
                part = part.substring(getCommandPrefix().length());
            }

            try {
                commands[i] = getCommand(part);
            } catch (CommandException e) {
                getException.accept(e);
            }
        }
        executeRecursive(commands, parts, 0, onFinish, getException);
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

    private Command getCommand(String line) throws RuntimeCommandException {
        line = line.trim();
        if (line.length() != 0) {
            String command = line;
            int firstSpace = line.indexOf(' ');
            if (firstSpace != -1) {
                command = line.substring(0, firstSpace);
            }

            if (!commandSheet.containsKey(command)) {
                throw new RuntimeCommandException("Command " + command + " does not exist.");
            }

            return commandSheet.get(command);
        }
        return null;

    }

    public Collection<Command> allCommands() {
        return commandSheet.values();
    }

    public Collection<String> allCommandNames() {
        return commandSheet.keySet();
    }

    public Command get(String name) {
        return (commandSheet.getOrDefault(name, null));
    }
}
