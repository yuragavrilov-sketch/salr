package ru.tcb.sal.commands.core.exception;

import ru.tcb.sal.commands.api.exception.RemoteBusinessException;
import ru.tcb.sal.commands.api.exception.RemoteCommandException;
import ru.tcb.sal.commands.api.exception.RemoteTechnicalException;
import ru.tcb.sal.commands.core.wire.InfrastructureExceptionDto;

/**
 * Maps InfrastructureExceptionDto from .NET to Java exception hierarchy.
 * Default: non-empty Code = business, else technical.
 */
public class ExceptionMapper {

    public RemoteCommandException map(InfrastructureExceptionDto dto) {
        if (dto == null) {
            return new RemoteTechnicalException("Unknown remote error", null, null, null);
        }

        boolean isBusiness = dto.code != null && !dto.code.isBlank();
        String message = dto.message != null ? dto.message : "Remote error";

        if (isBusiness) {
            return new RemoteBusinessException(
                "[" + dto.code + "] " + message,
                dto.exceptionType, dto.code, dto.adapterName);
        } else {
            return new RemoteTechnicalException(
                message, dto.exceptionType, dto.code, dto.adapterName);
        }
    }
}
