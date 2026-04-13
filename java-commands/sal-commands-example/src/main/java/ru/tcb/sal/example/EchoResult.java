package ru.tcb.sal.example;

import ru.tcb.sal.commands.api.CommandResult;
import ru.tcb.sal.commands.api.annotation.WireName;

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
