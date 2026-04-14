package ru.copperside.sal.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.copperside.sal.commands.api.annotation.CommandHandler;

@Component
public class EchoHandlers {

    private static final Logger log = LoggerFactory.getLogger(EchoHandlers.class);

    @CommandHandler
    public EchoResult echo(EchoCommand cmd) {
        log.info("Handling EchoCommand: message='{}'", cmd.message);
        return new EchoResult("ECHO: " + cmd.message, "sal-commands-example");
    }
}
