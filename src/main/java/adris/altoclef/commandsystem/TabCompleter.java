package adris.altoclef.commandsystem;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.args.Arg;
import adris.altoclef.commandsystem.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.event.events.TabCompleteEvent;
import baritone.api.event.listener.AbstractGameEventListener;

import java.util.stream.Stream;

// baritone already has that functionality, why bother creating the same mixin etc. again
public class TabCompleter implements AbstractGameEventListener {


    private static int lastIndexOfChars(String s, char... chars) {
        int index = -1;

        for (char ch : chars) {
            index = Math.max(index, s.lastIndexOf(ch));
        }

        return index;
    }

    @Override
    public void onPreTabComplete(TabCompleteEvent event) {
        CommandExecutor executor = AltoClef.getCommandExecutor();

        String prefix = event.prefix;

        if (!executor.isClientCommand(prefix)) {
            return;
        }

        Stream<String> completions;
        try {
            String call = prefix;
            if (call.contains(";")) {
                String[] split = call.split(";", -1);
                for (int i = 0; i < split.length - 1; i++) {
                    String s = split[i];
                    if (s.isBlank() || s.endsWith(" ")) {
                        event.completions = new String[0];
                        return;
                    }

                    s = s.stripLeading();

                    String[] parts = s.split(" ", -1);
                    String cmd = parts[0];
                    if (cmd.startsWith(executor.getCommandPrefix())) {
                        cmd = cmd.substring(executor.getCommandPrefix().length());
                    }

                    Command command = executor.get(cmd);
                    if (command == null) {
                        event.completions = new String[0];
                        return;
                    }

                    StringReader reader = new StringReader(s);
                    reader.next();

                    for (Arg<?> arg : command.getArgs()) {
                        Arg.ParseResult result = arg.consumeIfSupplied(reader);
                        if (result != Arg.ParseResult.CONSUMED) {
                            event.completions = new String[0];
                            return;
                        }
                    }
                }

                call = call.substring(call.lastIndexOf(";") + 1);
            }
            completions = getPossibleCompletions(call.stripLeading(), call.strip().startsWith(executor.getCommandPrefix()));
        } catch (CommandException e) {
            event.completions = new String[0];
            return;
        }

        int lastInd = Math.max(0, lastIndexOfChars(prefix, ' ', ',', '[', ']', ';') + 1);
        String comparing = prefix.substring(lastInd);

        String missing;
        if (prefix.lastIndexOf(' ') != lastInd - 1) {
            missing = prefix.substring(prefix.lastIndexOf(' ') + 1, lastInd);
        } else {
            missing = "";
        }

        event.completions = new TabCompleteHelper()
                .append(completions)
                .sortAlphabetically()
                .filterPrefix(comparing)
                .map(s -> missing + s)
                .build();
    }

    private Stream<String> getPossibleCompletions(String prefix, boolean needsPrefix) throws CommandException {
        CommandExecutor executor = AltoClef.getCommandExecutor();
        String[] parts = prefix.split(" ");


        if (parts.length == 0) {
            // autocomplete commands
            if (!needsPrefix) {
                return executor.allCommandNames().stream();
            }
            return executor.allCommandNames().stream().map(s -> executor.getCommandPrefix() + s);
        }
        if (parts.length == 1 && !prefix.endsWith(" ")) {
            if (parts[0].contains("[") || parts[0].contains(",")) { //kinda stupid hotfix but whatever
                return Stream.empty();
            }
            if (!needsPrefix) {
                return executor.allCommandNames().stream();
            }
            return executor.allCommandNames().stream().map(s -> executor.getCommandPrefix() + s);
        }

        // autocomplete command arguments
        String part = parts[0];
        if (needsPrefix) {
            part = part.substring(executor.getCommandPrefix().length());
        }
        Command command = executor.get(part);
        if (command == null) {
            return Stream.empty();
        }

        return command.resolveTabCompletions(prefix);
    }

}
