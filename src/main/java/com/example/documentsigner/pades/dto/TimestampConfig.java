package com.example.documentsigner.pades.dto;

/**
 * Configuration for RFC 3161 timestamp (TSA) augmentation of PAdES signatures.
 *
 * When provided, transforms the signature from PAdES-B (basic, local clock)
 * into PAdES-T (with trusted timestamp). The TSA token is embedded in the
 * CMS SignerInfo as a {@code signature-time-stamp} unsigned attribute.
 */
public class TimestampConfig {

    private static final String DEFAULT_TSA_URL = "http://timestamp.digicert.com";

    private final String tsaUrl;

    public TimestampConfig(String tsaUrl) {
        this.tsaUrl = (tsaUrl != null && !tsaUrl.isEmpty()) ? tsaUrl : resolveDefaultTsaUrl();
    }

    public static TimestampConfig defaultConfig() {
        return new TimestampConfig(resolveDefaultTsaUrl());
    }

    public String getTsaUrl() {
        return tsaUrl;
    }

    /**
     * Resolves the default TSA URL from the {@code TSA_URL} environment variable,
     * falling back to DigiCert's free public TSA (Adobe AATL trusted).
     */
    private static String resolveDefaultTsaUrl() {
        String env = System.getenv("TSA_URL");
        return (env != null && !env.isEmpty()) ? env : DEFAULT_TSA_URL;
    }
}
