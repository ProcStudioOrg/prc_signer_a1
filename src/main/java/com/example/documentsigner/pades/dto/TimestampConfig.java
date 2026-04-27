package com.example.documentsigner.pades.dto;

/**
 * Configuration for RFC 3161 timestamp (TSA) augmentation of PAdES signatures.
 *
 * When provided, transforms the signature from PAdES-B (basic, local clock)
 * into PAdES-T (with trusted timestamp). The TSA token is embedded in the
 * CMS SignerInfo as a {@code signature-time-stamp} unsigned attribute.
 */
public class TimestampConfig {

    // TODO(future): switch DEFAULT_TSA_URL to ICP-Brasil's official TSA
    //               (https://tsa.iti.gov.br) once we have the budget to pay
    //               for the AC-Carimbador credenciada subscription.
    //
    // Why DigiCert today (intentional):
    //   - Free, AATL-trusted (Adobe Reader shows green check globally).
    //   - Sufficient for international documents and internal use.
    //
    // Why tsa.iti.gov.br tomorrow:
    //   - Required for full legal force in Brazil under MP 2.200-2 / 2001
    //     (cartórios, processos judiciais, peticionamento eletrônico).
    //   - DigiCert is NOT credenciada na ICP-Brasil — its timestamps are
    //     legally weaker than ICP-Brasil-issued ones in BR courts.
    //
    // Migration path: set the TSA_URL env var to the ICP-Brasil endpoint
    // once subscribed; no code change needed beyond updating this default.
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
