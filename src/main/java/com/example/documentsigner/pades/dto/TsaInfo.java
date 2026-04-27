package com.example.documentsigner.pades.dto;

import java.util.Date;

/**
 * Information decoded from an RFC 3161 {@code id-aa-signatureTimeStampToken}
 * unsigned attribute attached to a CMS SignerInfo.
 *
 * Presence of a {@link TsaInfo} on a {@link SignatureDetails} indicates the
 * signature is at least PAdES-T (vs. plain PAdES-B with local clock).
 */
public class TsaInfo {

    private final Date timestamp;       // genTime — issued by the TSA
    private final String tsaName;       // CN of the TSA cert (best-effort, may be null)
    private final boolean tokenValid;   // RFC 3161 cryptographic verification of the TST itself

    public TsaInfo(Date timestamp, String tsaName, boolean tokenValid) {
        this.timestamp = timestamp;
        this.tsaName = tsaName;
        this.tokenValid = tokenValid;
    }

    public Date getTimestamp()    { return timestamp; }
    public String getTsaName()    { return tsaName; }
    public boolean isTokenValid() { return tokenValid; }
}
