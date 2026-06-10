package io.potok.definition;

public class InvalidDefinitionException extends RuntimeException {

    public InvalidDefinitionException(String message) {
        super(message);
    }

    public InvalidDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
