package com.example.documentsigner.pades;

import com.example.documentsigner.pades.dto.CertificateType;

import java.security.cert.X509Certificate;
import java.util.Locale;

/**
 * Classifica um certificado de signatário em {@link CertificateType} olhando
 * apenas para o próprio certificado folha — sem depender da cadeia completa,
 * que muitas vezes não vem embutida no documento (ex.: gov.br embute só a folha).
 *
 * <h3>Critérios (nesta ordem de precedência)</h3>
 * <ol>
 *   <li><b>ICP-Brasil</b> — presença de uma extensão OID {@code 2.16.76.1.3.x}
 *       (dados do titular pessoa física/jurídica no padrão ICP-Brasil), ou
 *       {@code O=ICP-Brasil} no subject, ou "ICP-Brasil" no issuer.</li>
 *   <li><b>gov.br</b> — issuer emitido pela CA "Gov-Br" / "Governo Federal do
 *       Brasil" (ex.: {@code CN=AC Final do Governo Federal do Brasil v1,
 *       O=Gov-Br, C=BR}).</li>
 *   <li><b>OTHER</b> — nenhum dos marcadores acima.</li>
 * </ol>
 *
 * <p>Base empírica: e-CPF A1 ICP-Brasil traz {@code O=ICP-Brasil} + OID
 * {@code 2.16.76.1.3.1}; assinaturas gov.br trazem issuer {@code O=Gov-Br}.</p>
 */
public final class CertificateTypeDetector {

    /**
     * OIDs ICP-Brasil de dados do titular (2.16.76.1.3.*). Presença de qualquer
     * uma é marcador forte de certificado ICP-Brasil.
     * .1 = pessoa física (e-CPF); .2/.3 = pessoa jurídica (responsável/e-CNPJ);
     * .4/.5/.6/.7 = dados adicionais (título, sanitário, etc.).
     */
    private static final String[] ICP_BRASIL_HOLDER_OIDS = {
        "2.16.76.1.3.1", "2.16.76.1.3.2", "2.16.76.1.3.3",
        "2.16.76.1.3.4", "2.16.76.1.3.5", "2.16.76.1.3.6", "2.16.76.1.3.7"
    };

    private CertificateTypeDetector() {
    }

    /**
     * @param cert certificado folha do signatário; {@code null} → {@link CertificateType#OTHER}
     */
    public static CertificateType detect(X509Certificate cert) {
        if (cert == null) {
            return CertificateType.OTHER;
        }

        String subject = upper(cert.getSubjectX500Principal().getName());
        String issuer = upper(cert.getIssuerX500Principal().getName());

        // 1. ICP-Brasil (cobre A1 e A3 — indistinguíveis pelo documento).
        if (hasIcpBrasilHolderExtension(cert)
                || subject.contains("O=ICP-BRASIL")
                || issuer.contains("ICP-BRASIL")) {
            return CertificateType.ICP_BRASIL;
        }

        // 2. gov.br (assinatura eletrônica avançada — CA "Gov-Br").
        if (issuer.contains("O=GOV-BR")
                || subject.contains("O=GOV-BR")
                || issuer.contains("GOVERNO FEDERAL DO BRASIL")) {
            return CertificateType.GOV_BR;
        }

        // 3. Qualquer outra origem.
        return CertificateType.OTHER;
    }

    private static boolean hasIcpBrasilHolderExtension(X509Certificate cert) {
        for (String oid : ICP_BRASIL_HOLDER_OIDS) {
            byte[] value = cert.getExtensionValue(oid);
            if (value != null && value.length > 0) {
                return true;
            }
        }
        return false;
    }

    private static String upper(String dn) {
        return dn == null ? "" : dn.toUpperCase(Locale.ROOT);
    }
}
