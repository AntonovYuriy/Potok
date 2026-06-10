package io.potok.common;

/** Human-readable failure text — never "null", always class + message of the root cause. */
public final class Errors {

    private Errors() {
    }

    public static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank() || "null".equals(message)) {
            message = t.getMessage();
        }
        String type = root.getClass().getSimpleName();
        if (message == null || message.isBlank() || "null".equals(message)) {
            return type;
        }
        return message.contains(type) ? message : type + ": " + message;
    }
}
