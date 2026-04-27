package com.example.documentsigner.pades.dto;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Aggregate verification result for a PDF that may contain one or more signatures.
 *
 * {@code valid} is the AND of every individual signature's validity — a single
 * compromised signer makes the whole document invalid.
 *
 * Convenience getters ({@link #getSignerName()}, {@link #getSigningTime()}, etc.)
 * delegate to the most recent (last-applied) signature, mirroring the legacy
 * single-signature contract.
 */
public class PdfVerificationResult {

    private boolean valid;
    private final List<SignatureDetails> signatures = new ArrayList<>();
    private String details;

    public boolean isValid()                       { return valid; }
    public void setValid(boolean valid)            { this.valid = valid; }

    public List<SignatureDetails> getSignatures()  { return signatures; }
    public int getTotalSignatures()                { return signatures.size(); }

    public String getDetails()                     { return details; }
    public void setDetails(String details)         { this.details = details; }

    // ─── Convenience getters: most recent signature ────────────────────────

    private SignatureDetails primary() {
        return signatures.isEmpty() ? null : signatures.get(signatures.size() - 1);
    }

    public String getSignerName() {
        SignatureDetails p = primary();
        return p == null ? null : p.getSignerName();
    }

    public Date getSigningTime() {
        SignatureDetails p = primary();
        return p == null ? null : p.getSigningTime();
    }

    public String getReason() {
        SignatureDetails p = primary();
        return p == null ? null : p.getReason();
    }

    public boolean isCertificateValid() {
        SignatureDetails p = primary();
        return p != null && p.isCertificateValid();
    }

    public boolean isIntegrityValid() {
        SignatureDetails p = primary();
        return p != null && p.isIntegrityValid();
    }

    public boolean isCoversWholeDocument() {
        SignatureDetails p = primary();
        return p != null && p.isCoversWholeDocument();
    }

    // ─── Builder ───────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final PdfVerificationResult result = new PdfVerificationResult();

        public Builder valid(boolean valid)   { result.setValid(valid);     return this; }
        public Builder details(String d)      { result.setDetails(d);       return this; }
        public Builder addSignature(SignatureDetails s) {
            result.signatures.add(s);
            return this;
        }
        public PdfVerificationResult build()  { return result; }
    }
}
