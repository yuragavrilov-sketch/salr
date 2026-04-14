package ru.copperside.sal.commands.core.wire;

import ru.copperside.sal.commands.api.Command;
import ru.copperside.sal.commands.api.CommandResult;
import ru.copperside.sal.commands.api.annotation.WireName;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Двусторонний реестр {@code Class <-> wire name}, построенный по
 * аннотации {@link WireName}. Fail-fast: отсутствие аннотации на
 * классе, реализующем {@link Command}/{@link CommandResult}, — ошибка
 * регистрации. У классов результата обязателен непустой
 * {@code assembly}, иначе собрать AssemblyQualifiedName невозможно.
 *
 * <p>Thread-safe для чтения после полной регистрации.
 */
public class WireTypeRegistry {

    private final Map<String, Class<?>> wireNameToClass = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> classToWireName = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> classToAssembly = new ConcurrentHashMap<>();

    public void register(Class<?> clazz) {
        WireName ann = clazz.getAnnotation(WireName.class);
        if (ann == null) {
            throw new IllegalStateException(
                "Class " + clazz.getName() + " implements Command/CommandResult but is not annotated with @WireName");
        }

        boolean isResult = CommandResult.class.isAssignableFrom(clazz);
        if (isResult && ann.assembly().isEmpty()) {
            throw new IllegalStateException(
                "CommandResult class " + clazz.getName() + " must declare @WireName.assembly");
        }

        String name = ann.value();
        Class<?> existing = wireNameToClass.putIfAbsent(name, clazz);
        if (existing != null && !existing.equals(clazz)) {
            throw new IllegalStateException(
                "Duplicate @WireName '" + name + "': " + existing.getName() + " and " + clazz.getName());
        }
        classToWireName.put(clazz, name);
        if (!ann.assembly().isEmpty()) {
            classToAssembly.put(clazz, ann.assembly());
        }
    }

    public String wireName(Class<?> clazz) {
        String name = classToWireName.get(clazz);
        if (name == null) {
            throw new IllegalStateException("Not registered: " + clazz.getName());
        }
        return name;
    }

    public String assembly(Class<?> clazz) {
        String asm = classToAssembly.get(clazz);
        if (asm == null) {
            throw new IllegalStateException("No assembly registered for " + clazz.getName());
        }
        return asm;
    }

    public String assemblyQualifiedName(Class<?> clazz) {
        return wireName(clazz) + ", " + assembly(clazz);
    }

    public Class<?> classByWireName(String wireName) {
        return wireNameToClass.get(wireName);
    }

    public boolean isRegistered(Class<?> clazz) {
        return classToWireName.containsKey(clazz);
    }

    public int size() {
        return classToWireName.size();
    }
}
