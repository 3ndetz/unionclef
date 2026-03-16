package adris.altoclef.commandsystem.exception;

public abstract class CommandException extends Exception {

    public CommandException(String message) {
        super(message);
    }

    public CommandException(String message, Exception child) {
        super(message, child);
    }
}
