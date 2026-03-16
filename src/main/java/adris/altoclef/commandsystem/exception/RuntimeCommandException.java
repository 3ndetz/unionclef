package adris.altoclef.commandsystem.exception;

public class RuntimeCommandException extends CommandException {
    public RuntimeCommandException(String message) {
        super(message);
    }

    public RuntimeCommandException(String message, Exception child) {
        super(message, child);
    }

}
