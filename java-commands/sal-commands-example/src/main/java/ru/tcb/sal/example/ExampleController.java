package ru.tcb.sal.example;

import org.springframework.web.bind.annotation.*;
import ru.tcb.sal.commands.api.CommandBus;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExampleController {

    private final CommandBus commandBus;

    public ExampleController(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "hello");
        EchoResult result = commandBus.execute(new EchoCommand(message));
        return Map.of(
            "echo", result.echo,
            "processedBy", result.processedBy
        );
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of("app", "sal-commands-example", "status", "running");
    }
}
