package org.unlaxer.tenure;

public class TenureException extends RuntimeException {
    private final String code;

    public TenureException(String message) { super(message); this.code = "TENURE_ERROR"; }
    public TenureException(String code, String message) { super("[" + code + "] " + message); this.code = code; }
    public String code() { return code; }
}
