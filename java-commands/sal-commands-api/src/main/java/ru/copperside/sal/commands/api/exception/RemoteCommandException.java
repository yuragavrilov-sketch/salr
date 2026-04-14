package ru.copperside.sal.commands.api.exception;

public sealed abstract class RemoteCommandException extends RuntimeException
    permits RemoteBusinessException, RemoteTechnicalException {

    private final String exceptionType;
    private final String code;
    private final String remoteAdapterName;

    protected RemoteCommandException(String message, String exceptionType, String code,
                                      String remoteAdapterName, Throwable cause) {
        super(message, cause);
        this.exceptionType = exceptionType;
        this.code = code;
        this.remoteAdapterName = remoteAdapterName;
    }

    public String exceptionType() { return exceptionType; }
    public String code() { return code; }
    public String remoteAdapterName() { return remoteAdapterName; }
}
