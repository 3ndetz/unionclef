package adris.altoclef.commandsystem.exception;

public class BadCommandSyntaxException extends CommandException {

    public BadCommandSyntaxException(String message) {
        super(message);
    }

    public BadCommandSyntaxException(String message, Exception child) {
        super(message, child);
    }

}
