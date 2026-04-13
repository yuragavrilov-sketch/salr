package ru.tcb.sal.example;

import ru.tcb.sal.commands.api.CommandWithResult;
import ru.tcb.sal.commands.api.annotation.WireName;

@WireName(value = "TCB.JavaDemo.Commands.EchoCommand", assembly = "TCB.JavaDemo")
public class EchoCommand implements CommandWithResult<EchoResult> {
    public String message;

    public EchoCommand() {}
    public EchoCommand(String message) { this.message = message; }
}
