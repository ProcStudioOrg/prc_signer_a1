package com.example.documentsigner.pades;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.documentsigner.pades.dto.CertificateType;
import com.example.documentsigner.pades.dto.PdfVerificationResult;
import com.example.documentsigner.pades.dto.SignatureDetails;

/**
 * PRC-1015 — o /verify/pdf passa a responder o eixo de CONFIANÇA por assinatura.
 *
 * <p>Fixtures reais, locais e ignoradas pelo Git em tests/verify/. Quando elas
 * estão disponíveis, o gov.br sem raiz embarcada fecha como
 * {@code untrusted/untrusted_root}: é o consumidor (Rails) que decide aceitar
 * gov.br por nome + conteúdo. A cobertura autocontida e obrigatória dos três
 * estados da cadeia vive em {@code CertificateChainVerifierTest}.</p>
 */
class PadesVerifyChainTest {

    private static final List<String> STATUSES = Arrays.asList("verified", "untrusted", "unverified");

    private static byte[] fixture(String name) throws Exception {
        Path p = Paths.get("tests", "verify", name);
        assumeTrue(Files.exists(p), "fixture real opcional ausente: " + p);
        return Files.readAllBytes(p);
    }

    @Test
    void govBrSemRaizEmbarcadaSaiComoUntrustedRoot() throws Exception {
        PdfVerificationResult r = new PadesSignerService().verifyPdfSignature(fixture("a1-para-govbr-1_assinado.pdf"));

        assertTrue(r.getTotalSignatures() >= 1);
        SignatureDetails s = r.getSignatures().get(r.getSignatures().size() - 1);
        assertEquals(CertificateType.GOV_BR, s.getCertificateType());
        assertTrue(s.isIntegrityValid(), "integridade do fixture real");
        assertEquals("untrusted", s.getChainStatus());
        assertEquals("untrusted_root", s.getChainReason());
        assertNotNull(s.getChainIssuer());
        // o eixo de validade não muda de significado por causa da cadeia
        assertEquals(s.isIntegrityValid() && s.isCertificateValid(), s.isValid() || !s.isCertificateValid());
    }

    @Test
    void todaAssinaturaCarregaChainStatusMesmoEmDocumentoMisto() throws Exception {
        PdfVerificationResult r = new PadesSignerService().verifyPdfSignature(fixture("mix-govbr-bruno-mais-a1.pdf"));

        assertTrue(r.getTotalSignatures() >= 2, "fixture misto tem gov.br + A1");
        // O A1 real (AC SyngularID / ICP-Brasil) fecha contra as raízes embarcadas —
        // é isto que permite ao Rails EXIGIR `verified` para ICP_BRASIL sem quebrar
        // assinatura legítima, e recusar o autoassinado que se diz ICP-Brasil.
        SignatureDetails a1 = r.getSignatures().stream()
            .filter(s -> s.getCertificateType() == CertificateType.ICP_BRASIL).findFirst().orElse(null);
        assertNotNull(a1, "fixture misto precisa ter uma assinatura ICP-Brasil");
        assertEquals("verified", a1.getChainStatus());
        assertEquals("chain_verified", a1.getChainReason());
        for (SignatureDetails s : r.getSignatures()) {
            assertNotNull(s.getChainStatus(), "assinatura " + s.getIndex() + " sem chainStatus");
            assertTrue(STATUSES.contains(s.getChainStatus()), s.getChainStatus());
            assertNotNull(s.getChainReason());
            assertFalse("chain_verified".equals(s.getChainReason()) && !"verified".equals(s.getChainStatus()));
        }
    }
}
