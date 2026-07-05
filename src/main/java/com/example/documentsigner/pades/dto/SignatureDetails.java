package com.example.documentsigner.pades.dto;

import java.util.Date;

/**
 * Detailed result for a single signature within a (possibly multi-signed) PDF.
 *
 * A document may carry many SignerInfo entries (sequential signers, e.g. a
 * contract signed by both parties). Each is represented by one of these.
 */
public class SignatureDetails {

    private int index;                       // 1-based position in the document
    private String signerName;
    private String cpf;                      // CPF do titular (só ICP-Brasil); null p/ gov.br
    private CertificateType certificateType; // origem do certificado: ICP-Brasil / gov.br / outro
    private Date signingTime;                // from PDF /M field — signer's clock, not trusted
    private String reason;
    private boolean valid;                   // overall: integrity AND certificate AND not revoked
    private boolean integrityValid;          // CMS digest matches signed bytes
    private boolean certificateValid;        // chain validates and not expired
    private boolean coversWholeDocument;     // ByteRange covers the whole file
    private TsaInfo tsa;                     // null if signature is plain PAdES-B
    private RevocationStatus revocationStatus;
    private String details;                  // free-form summary

    public int getIndex()                              { return index; }
    public void setIndex(int index)                    { this.index = index; }
    public String getSignerName()                      { return signerName; }
    public void setSignerName(String n)                { this.signerName = n; }
    public String getCpf()                             { return cpf; }
    public void setCpf(String cpf)                     { this.cpf = cpf; }
    public CertificateType getCertificateType()        { return certificateType; }
    public void setCertificateType(CertificateType t)  { this.certificateType = t; }
    public Date getSigningTime()                       { return signingTime; }
    public void setSigningTime(Date t)                 { this.signingTime = t; }
    public String getReason()                          { return reason; }
    public void setReason(String r)                    { this.reason = r; }
    public boolean isValid()                           { return valid; }
    public void setValid(boolean v)                    { this.valid = v; }
    public boolean isIntegrityValid()                  { return integrityValid; }
    public void setIntegrityValid(boolean v)           { this.integrityValid = v; }
    public boolean isCertificateValid()                { return certificateValid; }
    public void setCertificateValid(boolean v)         { this.certificateValid = v; }
    public boolean isCoversWholeDocument()             { return coversWholeDocument; }
    public void setCoversWholeDocument(boolean v)      { this.coversWholeDocument = v; }
    public TsaInfo getTsa()                            { return tsa; }
    public void setTsa(TsaInfo tsa)                    { this.tsa = tsa; }
    public RevocationStatus getRevocationStatus()      { return revocationStatus; }
    public void setRevocationStatus(RevocationStatus s){ this.revocationStatus = s; }
    public String getDetails()                         { return details; }
    public void setDetails(String d)                   { this.details = d; }
}
