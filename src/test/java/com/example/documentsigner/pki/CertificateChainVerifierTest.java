package com.example.documentsigner.pki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

/**
 * PRC-857 — a pergunta que estes testes fazem NÃO é "o certificado está no
 * prazo", é "a cadeia fecha contra alguém em quem confiamos".
 */
class CertificateChainVerifierTest {

    private static final char[] PW = "123456".toCharArray();

    // ---------- autoassinado: decidido SEM truststore ----------

    @Test
    void autoassinadoEhUntrustedMesmoSemTruststore() throws Exception {
        KeyPair kp = keyPair();
        X509Certificate self = selfSigned(kp, "CN=Teste Autoassinado");

        ChainVerification r = CertificateChainVerifier.verify(self, java.util.Collections.singletonList(self), null);

        assertEquals("untrusted", r.getChainStatus());
        assertEquals("self_signed", r.getChainReason());
    }

    /**
     * O caso que motiva o card: um autoassinado que MENTE dizendo ser
     * ICP-Brasil no subject. O detector de tipo cai nessa; a cadeia não.
     */
    @Test
    void autoassinadoQueSeDizIcpBrasilContinuaUntrusted() throws Exception {
        KeyPair kp = keyPair();
        X509Certificate mentiroso = selfSigned(kp, "O=ICP-Brasil, CN=Falso A1");

        ChainVerification r = CertificateChainVerifier.verify(mentiroso, java.util.Collections.singletonList(mentiroso), null);

        assertEquals("untrusted", r.getChainStatus());
        assertEquals("self_signed", r.getChainReason());
    }

    @Test
    void isSelfSignedExigeAssinaturaFechando() throws Exception {
        KeyPair a = keyPair();
        X509Certificate self = selfSigned(a, "CN=Raiz");
        assertTrue(CertificateChainVerifier.isSelfSigned(self));

        // Emitido por outra chave: subject != issuer, não é autoassinado.
        KeyPair folha = keyPair();
        X509Certificate emitido = issuedBy(folha, a, "CN=Folha", "CN=Raiz");
        assertFalse(CertificateChainVerifier.isSelfSigned(emitido));
    }

    // ---------- sem truststore: "não sei" nunca vira "confiável" ----------

    /**
     * Sem path explícito vale o bundle EMBARCADO (raízes da ICP-Brasil), então
     * um certificado de raiz desconhecida é recusado — não fica "não sei".
     */
    @Test
    void semPathExplicitoUsaOBundleEmbarcadoERecusaRaizEstranha() throws Exception {
        KeyPair raiz = keyPair();
        KeyPair folha = keyPair();
        X509Certificate emitido = issuedBy(folha, raiz, "CN=Folha", "CN=Raiz Estranha");

        ChainVerification r = CertificateChainVerifier.verify(emitido, java.util.Collections.singletonList(emitido), null);

        assertEquals("untrusted", r.getChainStatus());
        assertEquals("untrusted_root", r.getChainReason());
    }

    /**
     * O bundle precisa carregar. Se vier 0, todo certificado legítimo cai em
     * `unverified` sem ninguém entender por quê — foi exatamente o que
     * aconteceu enquanto o parse era feito em lote (uma raiz EC com parâmetros
     * explícitos derrubava as 12).
     */
    @Test
    void bundleEmbarcadoCarregaTodasAsRaizes() {
        assertTrue(CertificateChainVerifier.bundledAnchors().size() >= 12,
                "bundle da ICP-Brasil deveria trazer ao menos 12 raízes, veio "
                        + CertificateChainVerifier.bundledAnchors().size());
    }

    /**
     * Regressão do mesmo bug, isolada: um bundle com um bloco corrompido no
     * meio não pode zerar os certificados bons.
     */
    @Test
    void certificadoIlegivelNoMeioDoBundleNaoDerrubaOsBons(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        KeyPair boa = keyPair();
        X509Certificate certBom = selfSigned(boa, "CN=Raiz Boa");

        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN CERTIFICATE-----\nRUlUQSBQT0RSRQ==\n-----END CERTIFICATE-----\n");
        sb.append(new String(pemOf(certBom)));
        Path pem = tmp.resolve("misto.pem");
        Files.write(pem, sb.toString().getBytes());

        assertEquals(1, CertificateChainVerifier.loadAnchors(pem).size());
    }

    // ---------- com truststore: PKIX de verdade ----------

    @Test
    void comTruststoreDaRaizCertaFicaVerified(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        KeyPair raiz = keyPair();
        X509Certificate certRaiz = selfSigned(raiz, "CN=Raiz Confiavel");
        KeyPair folha = keyPair();
        X509Certificate emitido = issuedBy(folha, raiz, "CN=Folha", "CN=Raiz Confiavel");

        Path pem = tmp.resolve("truststore.pem");
        Files.write(pem, pemOf(certRaiz));

        ChainVerification r = CertificateChainVerifier.verify(emitido, java.util.Collections.singletonList(emitido), pem);

        assertEquals("verified", r.getChainStatus());
        assertEquals("chain_verified", r.getChainReason());
    }

    @Test
    void comTruststoreDeOutraRaizFicaUntrusted(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        KeyPair raizBoa = keyPair();
        X509Certificate certRaizBoa = selfSigned(raizBoa, "CN=Raiz Confiavel");

        KeyPair outraRaiz = keyPair();
        KeyPair folha = keyPair();
        X509Certificate emitidoPorOutra = issuedBy(folha, outraRaiz, "CN=Folha", "CN=Raiz Estranha");

        Path pem = tmp.resolve("truststore.pem");
        Files.write(pem, pemOf(certRaizBoa));

        ChainVerification r = CertificateChainVerifier.verify(emitidoPorOutra, java.util.Collections.singletonList(emitidoPorOutra), pem);

        assertEquals("untrusted", r.getChainStatus());
        assertEquals("untrusted_root", r.getChainReason());
    }

    @Test
    void truststoreIlegivelNaoViraConfiavel(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        KeyPair raiz = keyPair();
        KeyPair folha = keyPair();
        X509Certificate emitido = issuedBy(folha, raiz, "CN=Folha", "CN=Raiz");

        Path inexistente = tmp.resolve("nao-existe.pem");
        ChainVerification r = CertificateChainVerifier.verify(emitido, java.util.Collections.singletonList(emitido), inexistente);

        assertEquals("unverified", r.getChainStatus());
        assertEquals("no_truststore", r.getChainReason());
    }

    // ---------- pela porta do PKCS12, que é como a API entra ----------

    @Test
    void verificaAPartirDoPkcs12() throws Exception {
        KeyPair kp = keyPair();
        X509Certificate self = selfSigned(kp, "CN=Teste Pkcs12");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("a", kp.getPrivate(), PW, new java.security.cert.Certificate[] { self });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, PW);

        ChainVerification r = CertificateChainVerifier.verify(out.toByteArray(), "123456", null);

        assertEquals("untrusted", r.getChainStatus());
        assertEquals("self_signed", r.getChainReason());
    }

    @Test
    void senhaErradaNaoViraConfiavel() throws Exception {
        KeyPair kp = keyPair();
        X509Certificate self = selfSigned(kp, "CN=Teste");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("a", kp.getPrivate(), PW, new java.security.cert.Certificate[] { self });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, PW);

        ChainVerification r = CertificateChainVerifier.verify(out.toByteArray(), "errada", null);

        assertEquals("unverified", r.getChainStatus());
        assertEquals("error", r.getChainReason());
    }

    // ---------- helpers ----------

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair kp, String dn) throws Exception {
        return build(kp.getPublic(), kp, dn, dn);
    }

    private static X509Certificate issuedBy(KeyPair subject, KeyPair issuer, String subjectDn, String issuerDn)
            throws Exception {
        return build(subject.getPublic(), issuer, subjectDn, issuerDn);
    }

    private static X509Certificate build(java.security.PublicKey pub, KeyPair signer, String subjectDn,
            String issuerDn) throws Exception {
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                new X500Name(issuerDn),
                BigInteger.valueOf(now + (long) (Math.random() * 100000)),
                new Date(now - 86400000L),
                new Date(now + 365L * 86400000L),
                new X500Name(subjectDn),
                pub);
        b.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(true));
        ContentSigner cs = new JcaContentSignerBuilder("SHA256WithRSA").build(signer.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(cs));
    }

    private static byte[] pemOf(X509Certificate cert) throws Exception {
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(cert.getEncoded());
        return ("-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n").getBytes();
    }
}
