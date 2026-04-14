package ru.copperside.sal.commands.api.exception;

public final class RemoteBusinessException extends RemoteCommandException {
    public RemoteBusinessException(String message, String exceptionType, String code,
                                    String remoteAdapterName) {
        super(message, exceptionType, code, remoteAdapterName, null);
    }
}
