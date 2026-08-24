package adris.altoclef.commandsystem;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.args.Arg;
import adris.altoclef.commandsystem.exception.CommandException;

import java.util.stream.Stream;

// baritone already has that functionality, why bother creating the same mixin etc. again
public class TabCompleter {

    /**
     * The three-field stand-in for baritone's TabCompleteEvent (G-0, 2026-08-24).
     *
     * <p>The event carried a prefix in and a String[] out, and the listener interface existed
     * so the legacy engine could dispatch it. Nothing dispatches it now -- ChatInputSuggestorMixin
     * calls complete() directly -- so the whole event system reduces to this.
     */
    private static final class TabCompleteEvent {
        final String prefix;
        String[] completions = new String[0];
        TabCompleteEvent(String prefix) { this.prefix = prefix; }
    }


    /**
     * Direct tab-complete call (no baritone event system needed).
     * Used by ChatInputSuggestorMixin when shredder is in noop mode.
     */
    public static String[] complete(String prefix) {
        TabCompleteEvent event = new TabCompleteEvent(prefix);
        new TabCompleter().onPreTabComplete(event);
        return event.completions;
    }

    private static int lastIndexOfChars(String s, char... chars) {
        int index = -1;

        for (char ch : chars) {
            index = Math.max(index, s.lastIndexOf(ch));
        }

        return index;
    }

    private void onPreTabComplete(TabCompleteEvent event) {
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

        // TabCompleteHelper's chain, in plain Java: sort, keep the prefix matches, re-attach the
        // part the caller already typed. Same order and same filter, no legacy builder.
        final String miss = missing;
        event.completions = completions
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .filter(s -> s.toLowerCase().startsWith(comparing.toLowerCase()))
                .map(s -> miss + s)
                .toArray(String[]::new);
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
