package com.example.documentsigner.pades.dto;

/**
 * Origem/natureza do certificado que produziu uma assinatura.
 *
 * <p>Detectado a partir do certificado do signatário (folha) embutido no
 * documento. Ver {@code CertificateTypeDetector} para os critérios.</p>
 *
 * <p><b>Importante — A1 vs A3:</b> não é possível distinguir A1 de A3 a partir
 * de um documento já assinado. A diferença entre eles é apenas onde a chave
 * privada é guardada (arquivo .pfx para A1, token/smartcard para A3); o
 * certificado embutido e a assinatura resultante são idênticos. Por isso o
 * tipo {@link #ICP_BRASIL} cobre ambos.</p>
 */
public enum CertificateType {

    /** Certificado ICP-Brasil (e-CPF/e-CNPJ, A1 ou A3). Contém CPF/CNPJ. */
    ICP_BRASIL("ICP-Brasil (A1/A3)"),

    /** Assinatura eletrônica avançada gov.br (CA "Gov-Br"). Só o nome, sem CPF. */
    GOV_BR("gov.br"),

    /** Qualquer outro emissor (autoassinado, corporativo interno, estrangeiro). */
    OTHER("Outro/Desconhecido");

    private final String label;

    CertificateType(String label) {
        this.label = label;
    }

    /** Rótulo legível para exibição ao usuário. */
    public String getLabel() {
        return label;
    }
}
