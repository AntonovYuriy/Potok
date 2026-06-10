package io.potok.action;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ActionRegistry {

    private final Map<String, ActionHandler> handlers;

    public ActionRegistry(List<ActionHandler> handlers) {
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(ActionHandler::type, Function.identity()));
    }

    /** @return handler for the action type, or null when unknown */
    public ActionHandler find(String type) {
        return handlers.get(type);
    }

    public java.util.Set<String> types() {
        return handlers.keySet();
    }
}
