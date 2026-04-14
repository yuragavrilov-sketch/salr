package ru.copperside.sal.example;

import ru.copperside.sal.commands.api.CommandWithResult;
import ru.copperside.sal.commands.api.annotation.WireName;

@WireName(value = "TCB.JavaDemo.Commands.EchoCommand", assembly = "TCB.JavaDemo")
public class EchoCommand implements CommandWithResult<EchoResult> {
    public String message;

    public EchoCommand() {}
    public EchoCommand(String message) { this.message = message; }
}
