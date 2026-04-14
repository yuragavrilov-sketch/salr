package ru.copperside.sal.commands.api.exception;

public final class RemoteTechnicalException extends RemoteCommandException {
    public RemoteTechnicalException(String message, String exceptionType, String code,
                                     String remoteAdapterName) {
        super(message, exceptionType, code, remoteAdapterName, null);
    }
}
