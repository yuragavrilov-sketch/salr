package ru.tcb.sal.commands.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class SalSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode data;
    private final Set<String> changedKeys = new LinkedHashSet<>();

    private SalSession(ObjectNode data) {
        this.data = data;
    }

    public static SalSession empty() {
        return new SalSession(MAPPER.createObjectNode());
    }

    public static SalSession from(ObjectNode data) {
        return new SalSession(data != null ? data : MAPPER.createObjectNode());
    }

    // Typed getters for well-known fields
    public String getSessionId() { return text("SessionId", ""); }
    public long getOperationId() { return number("OperationId", 0L); }
    public Long getHierarchyId() { return nullableLong("HierarchyId"); }
    public Long getAuthId() { return nullableLong("AuthId"); }

    // Generic access
    @SuppressWarnings("unchecked")
    public <T> Optional<T> tryGetValue(String key, Class<T> type) {
        var node = data.get(key);
        if (node == null || node.isNull()) return Optional.empty();
        try {
            return Optional.of(MAPPER.treeToValue(node, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public <T> T getSafeValue(String key, T defaultValue) {
        var node = data.get(key);
        if (node == null || node.isNull()) return defaultValue;
        try {
            @SuppressWarnings("unchecked")
            T value = (T) MAPPER.treeToValue(node, defaultValue.getClass());
            return value;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // Mutation
    public void addOrUpdate(String key, Object value) {
        data.set(key, MAPPER.valueToTree(value));
        changedKeys.add(key);
    }

    public void remove(String key) {
        if (data.remove(key) != null) {
            changedKeys.add(key);
        }
    }

    // Delta
    public boolean isChanged() { return !changedKeys.isEmpty(); }

    public ObjectNode getChangedData() {
        ObjectNode update = MAPPER.createObjectNode();
        ArrayNode removed = MAPPER.createArrayNode();
        for (String k : changedKeys) {
            if (data.has(k)) {
                update.set(k, data.get(k));
            } else {
                removed.add(k);
            }
        }
        changedKeys.clear();
        ObjectNode result = MAPPER.createObjectNode();
        if (!update.isEmpty()) result.set("updateValues", update);
        if (!removed.isEmpty()) result.set("removeValues", removed);
        return result;
    }

    public ObjectNode snapshot() { return data.deepCopy(); }

    // Helpers
    private String text(String key, String def) {
        var n = data.get(key);
        return n != null && !n.isNull() ? n.asText(def) : def;
    }

    private long number(String key, long def) {
        var n = data.get(key);
        return n != null && !n.isNull() ? n.asLong(def) : def;
    }

    private Long nullableLong(String key) {
        var n = data.get(key);
        return n != null && !n.isNull() ? n.asLong() : null;
    }
}
