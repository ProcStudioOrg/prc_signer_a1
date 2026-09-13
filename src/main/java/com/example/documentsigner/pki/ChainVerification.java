package com.example.documentsigner.pki;

/**
 * Resultado da verificação de cadeia. Imutável e serializável na API.
 */
public class ChainVerification {

    private final ChainStatus status;
    private final String reason;
    private final String issuer;

    public ChainVerification(ChainStatus status, String reason, String issuer) {
        this.status = status;
        this.reason = reason;
        this.issuer = issuer;
    }

    public static ChainVerification verified(String issuer) {
        return new ChainVerification(ChainStatus.VERIFIED, "chain_verified", issuer);
    }

    public static ChainVerification untrusted(String reason, String issuer) {
        return new ChainVerification(ChainStatus.UNTRUSTED, reason, issuer);
    }

    public static ChainVerification unverified(String reason, String issuer) {
        return new ChainVerification(ChainStatus.UNVERIFIED, reason, issuer);
    }

    /** `verified` | `untrusted` | `unverified` — é isto que o consumidor grava. */
    public String getChainStatus() {
        return status.wire();
    }

    /**
     * Motivo legível por máquina: `chain_verified`, `self_signed`,
     * `untrusted_root`, `no_truststore`, `incomplete_chain`, `error`.
     */
    public String getChainReason() {
        return reason;
    }

    /** Issuer do certificado final, para exibição. */
    public String getChainIssuer() {
        return issuer;
    }

    public ChainStatus status() {
        return status;
    }

    @Override
    public String toString() {
        return "ChainVerification{" + status.wire() + ", " + reason + "}";
    }
}
