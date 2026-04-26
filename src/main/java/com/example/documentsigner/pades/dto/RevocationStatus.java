package com.example.documentsigner.pades.dto;

import java.util.Date;

/**
 * Result of an OCSP / CRL revocation check on a certificate.
 *
 * Modeled as a small value object — soft-fail by design: when the responder
 * is unreachable or the cert lacks an OCSP/CRL URL, {@code state} is
 * {@code UNKNOWN} rather than {@code REVOKED}, and signature validity should
 * not be downgraded purely on that basis.
 */
public class RevocationStatus {

    public enum State {
        GOOD,        // Responder confirmed cert is not revoked
        REVOKED,     // Responder confirmed cert is revoked
        UNKNOWN,     // Responder responded but couldn't determine
        NOT_CHECKED, // Cert had no OCSP URL / no issuer / check skipped
        ERROR        // Network / parsing error
    }

    private final State state;
    private final String details;
    private final Date checkedAt;
    private final Date revokedAt; // only set when REVOKED

    private RevocationStatus(State state, String details, Date revokedAt) {
        this.state = state;
        this.details = details;
        this.checkedAt = new Date();
        this.revokedAt = revokedAt;
    }

    public static RevocationStatus good(String details) {
        return new RevocationStatus(State.GOOD, details, null);
    }

    public static RevocationStatus revoked(String details, Date revokedAt) {
        return new RevocationStatus(State.REVOKED, details, revokedAt);
    }

    public static RevocationStatus unknown(String details) {
        return new RevocationStatus(State.UNKNOWN, details, null);
    }

    public static RevocationStatus notChecked(String details) {
        return new RevocationStatus(State.NOT_CHECKED, details, null);
    }

    public static RevocationStatus error(String details) {
        return new RevocationStatus(State.ERROR, details, null);
    }

    public State getState()      { return state; }
    public String getDetails()   { return details; }
    public Date getCheckedAt()   { return checkedAt; }
    public Date getRevokedAt()   { return revokedAt; }
}
