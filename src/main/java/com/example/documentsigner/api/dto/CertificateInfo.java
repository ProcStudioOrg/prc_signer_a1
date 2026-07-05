package com.example.documentsigner.api.dto;

import java.util.Date;

/**
 * DTO containing certificate details for display and validation.
 */
public class CertificateInfo {
    private boolean valid;
    private String subject;
    private String commonName;
    private String issuer;
    private String serialNumber;
    private Date notBefore;
    private Date notAfter;
    private boolean expired;
    private long daysUntilExpiry;
    private String algorithm;
    private String certificateType;       // ICP_BRASIL | GOV_BR | OTHER
    private String certificateTypeLabel;  // rótulo legível
    private String error;

    public CertificateInfo() {
    }

    // Constructor for error cases
    public CertificateInfo(boolean valid, String error) {
        this.valid = valid;
        this.error = error;
    }

    // Full constructor
    public CertificateInfo(boolean valid, String subject, String commonName, String issuer,
                          String serialNumber, Date notBefore, Date notAfter,
                          boolean expired, long daysUntilExpiry, String algorithm) {
        this.valid = valid;
        this.subject = subject;
        this.commonName = commonName;
        this.issuer = issuer;
        this.serialNumber = serialNumber;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.expired = expired;
        this.daysUntilExpiry = daysUntilExpiry;
        this.algorithm = algorithm;
    }

    // Getters and setters
    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getCommonName() {
        return commonName;
    }

    public void setCommonName(String commonName) {
        this.commonName = commonName;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public Date getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Date notBefore) {
        this.notBefore = notBefore;
    }

    public Date getNotAfter() {
        return notAfter;
    }

    public void setNotAfter(Date notAfter) {
        this.notAfter = notAfter;
    }

    public boolean isExpired() {
        return expired;
    }

    public void setExpired(boolean expired) {
        this.expired = expired;
    }

    public long getDaysUntilExpiry() {
        return daysUntilExpiry;
    }

    public void setDaysUntilExpiry(long daysUntilExpiry) {
        this.daysUntilExpiry = daysUntilExpiry;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getCertificateType() {
        return certificateType;
    }

    public void setCertificateType(String certificateType) {
        this.certificateType = certificateType;
    }

    public String getCertificateTypeLabel() {
        return certificateTypeLabel;
    }

    public void setCertificateTypeLabel(String certificateTypeLabel) {
        this.certificateTypeLabel = certificateTypeLabel;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    @Override
    public String toString() {
        if (!valid) {
            return "Certificate Error: " + error;
        }
        return String.format(
            "Subject: %s\n" +
            "Common Name: %s\n" +
            "Issuer: %s\n" +
            "Serial Number: %s\n" +
            "Valid From: %s\n" +
            "Valid Until: %s\n" +
            "Status: %s\n" +
            "Days Until Expiry: %d\n" +
            "Algorithm: %s",
            subject, commonName, issuer, serialNumber,
            notBefore, notAfter,
            expired ? "EXPIRED" : "VALID",
            daysUntilExpiry, algorithm
        );
    }
}
