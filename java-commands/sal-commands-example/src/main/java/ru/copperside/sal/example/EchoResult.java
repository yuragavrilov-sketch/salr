package ru.copperside.sal.example;

import ru.copperside.sal.commands.api.CommandResult;
import ru.copperside.sal.commands.api.annotation.WireName;

@WireName(value = "TCB.JavaDemo.Results.EchoResult", assembly = "TCB.JavaDemo")
public class EchoResult implements CommandResult {
    public String echo;
    public String processedBy;

    public EchoResult() {}
    public EchoResult(String echo, String processedBy) {
        this.echo = echo;
        this.processedBy = processedBy;
    }
}
