import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class JavaUtilLoggingExamples {

    private static final Logger LOGGER = Logger.getLogger(JavaUtilLoggingExamples.class.getName());

    public static void main(String[] args) {
        LOGGER.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new SimpleFormatter());
        LOGGER.addHandler(handler);
        LOGGER.setLevel(Level.ALL);

        LOGGER.info("application started");
        LOGGER.warning("this is a warning");
        LOGGER.fine("fine-grained debug message");

        try {
            riskyOperation();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.SEVERE, "operation failed", ex);
        }
    }

    private static void riskyOperation() {
        throw new IllegalStateException("example failure");
    }
}
