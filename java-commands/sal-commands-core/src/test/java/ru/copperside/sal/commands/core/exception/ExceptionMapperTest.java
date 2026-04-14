package ru.copperside.sal.commands.core.exception;

import org.junit.jupiter.api.Test;
import ru.copperside.sal.commands.api.exception.RemoteBusinessException;
import ru.copperside.sal.commands.api.exception.RemoteTechnicalException;
import ru.copperside.sal.commands.core.wire.InfrastructureExceptionDto;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionMapperTest {

    private final ExceptionMapper mapper = new ExceptionMapper();

    @Test
    void nonEmptyCode_mapsToBusiness() {
        InfrastructureExceptionDto dto = new InfrastructureExceptionDto();
        dto.code = "PAYMENT_DECLINED";
        dto.message = "Insufficient funds";
        dto.exceptionType = "BusinessException";
        dto.adapterName = "PaymentAdapter";

        var ex = mapper.map(dto);

        assertThat(ex).isInstanceOf(RemoteBusinessException.class);
        assertThat(ex.code()).isEqualTo("PAYMENT_DECLINED");
        assertThat(ex.getMessage()).contains("Insufficient funds");
        assertThat(ex.remoteAdapterName()).isEqualTo("PaymentAdapter");
    }

    @Test
    void emptyCode_mapsToTechnical() {
        InfrastructureExceptionDto dto = new InfrastructureExceptionDto();
        dto.message = "Connection timeout";
        dto.exceptionType = "TimeoutException";

        var ex = mapper.map(dto);

        assertThat(ex).isInstanceOf(RemoteTechnicalException.class);
        assertThat(ex.getMessage()).isEqualTo("Connection timeout");
    }

    @Test
    void nullDto_returnsTechnical() {
        var ex = mapper.map(null);
        assertThat(ex).isInstanceOf(RemoteTechnicalException.class);
    }

    @Test
    void realDotNetFatalException() {
        // From real wire dump (msg-003): Code="FatalException"
        InfrastructureExceptionDto dto = new InfrastructureExceptionDto();
        dto.exceptionType = "Fatal";
        dto.code = "FatalException";
        dto.message = "Sequence contains no matching element";
        dto.adapterName = "TCB.MercLibrary";

        var ex = mapper.map(dto);

        assertThat(ex).isInstanceOf(RemoteBusinessException.class); // has code
        assertThat(ex.code()).isEqualTo("FatalException");
    }
}
