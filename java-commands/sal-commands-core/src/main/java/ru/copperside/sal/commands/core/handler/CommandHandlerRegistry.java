package ru.copperside.sal.commands.core.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CommandHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandHandlerRegistry.class);
    private final Map<String, HandlerBinding> bindings = new ConcurrentHashMap<>();

    public void register(HandlerBinding binding) {
        HandlerBinding existing = bindings.putIfAbsent(binding.wireName(), binding);
        if (existing != null) {
            String existingName = existing.bean() != null ? existing.bean().getClass().getName() : "null";
            String newName = binding.bean() != null ? binding.bean().getClass().getName() : "null";
            throw new IllegalStateException(
                "Duplicate @CommandHandler for '" + binding.wireName() + "': "
                    + existingName + " and " + newName);
        }
        String beanName = binding.bean() != null ? binding.bean().getClass().getSimpleName() : "<static>";
        log.info("[REGISTRY] Registered: '{}' -> {}", binding.wireName(), beanName);
    }

    public HandlerBinding find(String wireName) { return bindings.get(wireName); }
    public Collection<HandlerBinding> all() { return bindings.values(); }
    public int size() { return bindings.size(); }
}
