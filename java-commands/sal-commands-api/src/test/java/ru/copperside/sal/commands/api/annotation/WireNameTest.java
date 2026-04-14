package ru.copperside.sal.commands.api.annotation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WireNameTest {

    @WireName("TCB.Payment.Request.CreatePaymentCommand")
    private static class PaymentCmd {}

    @WireName(value = "TCB.Payment.Contracts.CreatePaymentResult",
              assembly = "TCB.Payment.Contracts")
    private static class PaymentResult {}

    @Test
    void annotationOnCommand_hasWireName_noAssembly() {
        WireName ann = PaymentCmd.class.getAnnotation(WireName.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo("TCB.Payment.Request.CreatePaymentCommand");
        assertThat(ann.assembly()).isEmpty();
    }

    @Test
    void annotationOnResult_hasBothValueAndAssembly() {
        WireName ann = PaymentResult.class.getAnnotation(WireName.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo("TCB.Payment.Contracts.CreatePaymentResult");
        assertThat(ann.assembly()).isEqualTo("TCB.Payment.Contracts");
    }
}
