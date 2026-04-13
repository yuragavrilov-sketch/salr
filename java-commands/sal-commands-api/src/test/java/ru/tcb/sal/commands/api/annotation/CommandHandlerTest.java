package ru.tcb.sal.commands.api.annotation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommandHandlerTest {
    @CommandHandler
    void noArgs() {}

    @CommandHandler(value = "TCB.Test.Cmd", onError = ErrorPolicy.REJECT_TO_DLQ)
    void withArgs() {}

    @Test
    void defaultValues() throws Exception {
        CommandHandler ann = getClass().getDeclaredMethod("noArgs").getAnnotation(CommandHandler.class);
        assertThat(ann.value()).isEmpty();
        assertThat(ann.onError()).isEqualTo(ErrorPolicy.REPLY_WITH_FAILURE);
    }

    @Test
    void explicitValues() throws Exception {
        CommandHandler ann = getClass().getDeclaredMethod("withArgs").getAnnotation(CommandHandler.class);
        assertThat(ann.value()).isEqualTo("TCB.Test.Cmd");
        assertThat(ann.onError()).isEqualTo(ErrorPolicy.REJECT_TO_DLQ);
    }
}
