package com.example.documentsigner.pki;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confere criptograficamente a cadeia de um certificado A1 contra o truststore
 * da ICP-Brasil.
 *
 * <p><b>Por que aqui e não no ProcStudio (Rails).</b> Este serviço já é o dono
 * do certificado — recebe o {@code .pfx} e a senha para assinar — e a JVM traz
 * PKIX pronto ({@link CertPathValidator}). Fazer o mesmo em Ruby seria
 * reimplementar o que a plataforma já faz certo, e manter o truststore em dois
 * containers que divergem. O consumidor grava o resultado; a pergunta é
 * respondida aqui.</p>
 *
 * <p><b>O que isto NÃO é.</b> {@code CertificateTypeDetector} classifica por
 * marcadores DECLARADOS no próprio certificado (OID 2.16.76.1.3.x,
 * {@code O=ICP-Brasil} no subject, "ICP-Brasil" no issuer). Isso é
 * auto-declaração: um autoassinado gerado com
 * {@code -subj "/O=ICP-Brasil/..."} passa por lá. Esta classe pergunta outra
 * coisa — se a assinatura fecha contra uma raiz em que confiamos.</p>
 *
 * <p><b>Autoassinado não precisa de truststore.</b> {@code subject == issuer} e
 * a assinatura fechando com a própria chave pública é prova suficiente, e
 * autoassinado nunca é ICP-Brasil. Por isso o caso mais comum de fraude fecha
 * mesmo em ambiente sem truststore, como dev e CI.</p>
 *
 * <p><b>Revogação (OCSP/CRL) fica de fora nesta versão</b> — de propósito:
 * exige rede na hora do upload e transformaria indisponibilidade do provedor em
 * recusa de certificado bom. Ver {@code IMPACTO-CONSUMIDORES.md}.</p>
 */
public final class CertificateChainVerifier {

    /** Caminho do truststore. Aceita bundle PEM ou keystore JKS/PKCS12. */
    public static final String TRUSTSTORE_ENV = "ICP_BRASIL_TRUSTSTORE_PATH";

    /**
     * {@code unverified} NUNCA sai em silêncio (Bruno, 2026-09-12). É o Signer
     * dizendo "não consegui decidir" — sem âncoras, bundle ausente, exceção no
     * PKIX — e o consumidor responde 503 ao cliente por causa disso. Cada um
     * desses caminhos loga em ERROR com a causa, para o operador ver a falha
     * nossa em vez de o cliente reassinar em loop com um certificado bom.
     */
    private static final Logger log = LoggerFactory.getLogger(CertificateChainVerifier.class);

    static {
        // BouncyCastle já é dependência (bcprov/bcpkix). Registrado aqui porque
        // ele lê certificados que o provider padrão da JDK recusa — caso real
        // no repositório de raízes da ICP-Brasil.
        try {
            if (java.security.Security.getProvider("BC") == null) {
                java.security.Security.addProvider(
                        new org.bouncycastle.jce.provider.BouncyCastleProvider());
            }
        } catch (Throwable ignored) {
            // sem BC o parse cai no provider padrão
        }
    }

    private CertificateChainVerifier() {
    }

    public static ChainVerification verify(byte[] pkcs12Bytes, String password) {
        return verify(pkcs12Bytes, password, truststorePathFromEnv());
    }

    /**
     * Para quem já abriu o PKCS12 e tem o certificado em mãos — evita reabrir e
     * repedir a senha. Usa o truststore do ambiente.
     */
    public static ChainVerification verify(X509Certificate leaf, List<X509Certificate> chain) {
        return verify(leaf, chain, truststorePathFromEnv());
    }

    static ChainVerification verify(byte[] pkcs12Bytes, String password, Path truststorePath) {
        X509Certificate leaf = null;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pkcs12Bytes), password.toCharArray());

            String alias = firstAliasWithCertificate(ks);
            if (alias == null) {
                return ChainVerification.unverified("no_certificate", null);
            }

            leaf = (X509Certificate) ks.getCertificate(alias);
            List<X509Certificate> chain = chainOf(ks, alias);
            return verify(leaf, chain, truststorePath);

        } catch (Exception e) {
            // PKCS12 que não abre (senha errada, arquivo corrompido) é erro do
            // chamador, não nosso: WARN com a causa, sem stack trace.
            log.warn("Cadeia não conferida a partir do PKCS12 (chainStatus=unverified, reason=error): {}",
                    e.toString());
            return ChainVerification.unverified("error", issuerOf(leaf));
        }
    }

    static ChainVerification verify(X509Certificate leaf, List<X509Certificate> chain, Path truststorePath) {
        if (leaf == null) {
            return ChainVerification.unverified("no_certificate", null);
        }
        String issuer = issuerOf(leaf);

        // 1. Autoassinado: decidido sem truststore nenhum.
        if (isSelfSigned(leaf)) {
            return ChainVerification.untrusted("self_signed", issuer);
        }

        // 2. Sem truststore não dá para afirmar confiança — e "não sei" nunca
        //    pode virar "confiável".
        Set<TrustAnchor> anchors = truststorePath == null
                ? effectiveAnchors()
                : loadAnchors(truststorePath);
        if (anchors.isEmpty()) {
            log.error("Sem âncoras ICP-Brasil (truststore={}): cadeia de '{}' sai unverified/no_truststore. "
                    + "Falha de configuração do Signer, NÃO do certificado — o consumidor vai responder 503.",
                    truststorePath == null ? "bundle embarcado + " + TRUSTSTORE_ENV : truststorePath, issuer);
            return ChainVerification.unverified("no_truststore", issuer);
        }

        // 3. PKIX de verdade.
        try {
            List<X509Certificate> path = new ArrayList<>();
            path.add(leaf);
            for (X509Certificate c : chain) {
                if (!c.equals(leaf) && !isSelfSigned(c)) {
                    path.add(c);
                }
            }

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            CertPath certPath = cf.generateCertPath(path);

            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false); // ver javadoc da classe

            CertPathValidator.getInstance("PKIX").validate(certPath, params);
            return ChainVerification.verified(issuer);

        } catch (CertPathValidatorException e) {
            return ChainVerification.untrusted("untrusted_root", issuer);
        } catch (Exception e) {
            log.error("Exceção na validação PKIX da cadeia de '{}': sai unverified/error "
                    + "(falha do Signer, não do certificado)", issuer, e);
            return ChainVerification.unverified("error", issuer);
        }
    }

    /**
     * {@code subject == issuer} E a assinatura fecha com a própria chave
     * pública. Só o primeiro seria fraco: qualquer um escreve o que quiser no
     * subject.
     */
    static boolean isSelfSigned(X509Certificate cert) {
        if (!cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) {
            return false;
        }
        try {
            cert.verify(cert.getPublicKey());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String firstAliasWithCertificate(KeyStore ks) throws Exception {
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (ks.getCertificate(alias) instanceof X509Certificate) {
                return alias;
            }
        }
        return null;
    }

    private static List<X509Certificate> chainOf(KeyStore ks, String alias) throws Exception {
        List<X509Certificate> out = new ArrayList<>();
        java.security.cert.Certificate[] raw = ks.getCertificateChain(alias);
        if (raw != null) {
            for (java.security.cert.Certificate c : raw) {
                if (c instanceof X509Certificate) {
                    out.add((X509Certificate) c);
                }
            }
        }
        return out;
    }

    static Path truststorePathFromEnv() {
        String configured = System.getenv(TRUSTSTORE_ENV);
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        return Paths.get(configured.trim());
    }

    /**
     * Âncoras efetivas: o truststore do ambiente tem precedência; sem ele, vale
     * o bundle embarcado no jar.
     *
     * <p>O bundle vai junto de propósito. Deixar a verificação dependendo de uma
     * variável de ambiente significa que ela nasce desligada, e um recurso de
     * segurança desligado por omissão é um recurso que não existe — todo
     * certificado legítimo apareceria como {@code unverified} até alguém
     * lembrar de configurar. As raízes da ICP-Brasil são públicas, então não há
     * segredo para manter fora da imagem.</p>
     */
    static Set<TrustAnchor> effectiveAnchors() {
        Path fromEnv = truststorePathFromEnv();
        if (fromEnv != null) {
            Set<TrustAnchor> configured = loadAnchors(fromEnv);
            if (!configured.isEmpty()) {
                return configured;
            }
        }
        return bundledAnchors();
    }

    /** Raízes públicas da ICP-Brasil embarcadas — ver resources/pki/README.md. */
    static Set<TrustAnchor> bundledAnchors() {
        Set<TrustAnchor> anchors = new HashSet<>();
        try (InputStream in = CertificateChainVerifier.class
                .getResourceAsStream("/pki/icp-brasil-roots.pem")) {
            if (in == null) {
                log.error("Bundle embarcado /pki/icp-brasil-roots.pem ausente do jar — toda cadeia ICP-Brasil "
                        + "sairá unverified/no_truststore até corrigir o build");
                return anchors;
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            String text = new String(buf.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
            for (byte[] der : pemBlocks(text)) {
                X509Certificate cert = parseCertificate(der);
                if (cert != null) {
                    anchors.add(new TrustAnchor(cert, null));
                }
            }
        } catch (Exception e) {
            // sem bundle, sobra o comportamento de "não sei" — mas nunca em silêncio
            log.error("Falha ao carregar o bundle embarcado /pki/icp-brasil-roots.pem", e);
        }
        if (anchors.isEmpty()) {
            log.error("Bundle embarcado /pki/icp-brasil-roots.pem não rendeu nenhuma âncora");
        }
        return anchors;
    }

    /**
     * Aceita bundle PEM (vários certificados concatenados) ou JKS/PKCS12.
     *
     * <p><b>Parseia um certificado por vez, de propósito.</b>
     * {@code generateCertificates} sobre o stream inteiro aborta tudo no
     * primeiro certificado que o provider não entende — e o repositório oficial
     * da ICP-Brasil tem pelo menos uma raiz que o provider padrão da JDK recusa
     * com {@code Only named ECParameters supported}. Com o parse em lote, UMA
     * raiz exótica zerava as âncoras e todo certificado legítimo virava
     * {@code unverified} em silêncio. Aqui a ruim é pulada e as boas entram.</p>
     *
     * <p>O BouncyCastle já é dependência do projeto e entende os casos que o
     * provider padrão recusa, então é tentado antes.</p>
     */
    static Set<TrustAnchor> loadAnchors(Path truststorePath) {
        Set<TrustAnchor> anchors = new HashSet<>();
        if (truststorePath == null) {
            return anchors;
        }
        if (!Files.isReadable(truststorePath)) {
            log.error("Truststore configurado em {}={} não existe ou não é legível", TRUSTSTORE_ENV, truststorePath);
            return anchors;
        }
        try {
            byte[] all = Files.readAllBytes(truststorePath);
            String text = new String(all, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (text.contains(PEM_BEGIN)) {
                for (byte[] der : pemBlocks(text)) {
                    X509Certificate cert = parseCertificate(der);
                    if (cert != null) {
                        anchors.add(new TrustAnchor(cert, null));
                    }
                }
            } else {
                X509Certificate single = parseCertificate(all);
                if (single != null) {
                    anchors.add(new TrustAnchor(single, null));
                }
            }
        } catch (Exception e) {
            // cai para o keystore abaixo
            log.warn("Truststore {} não parseou como PEM ({}); tentando JKS/PKCS12", truststorePath, e.toString());
        }
        if (anchors.isEmpty()) {
            anchors.addAll(loadAnchorsFromKeyStore(truststorePath));
        }
        if (anchors.isEmpty()) {
            log.error("Truststore {} não rendeu nenhuma âncora (nem PEM, nem JKS, nem PKCS12) — "
                    + "toda cadeia ICP-Brasil sairá unverified/no_truststore", truststorePath);
        }
        return anchors;
    }

    private static final String PEM_BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String PEM_END = "-----END CERTIFICATE-----";

    /** Quebra o bundle em blocos DER, um por certificado. */
    private static List<byte[]> pemBlocks(String text) {
        List<byte[]> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int begin = text.indexOf(PEM_BEGIN, from);
            if (begin < 0) {
                return out;
            }
            int end = text.indexOf(PEM_END, begin);
            if (end < 0) {
                return out;
            }
            String body = text.substring(begin + PEM_BEGIN.length(), end)
                    .replaceAll("\\s", "");
            try {
                out.add(java.util.Base64.getDecoder().decode(body));
            } catch (Exception malformed) {
                // bloco corrompido não derruba os demais
            }
            from = end + PEM_END.length();
        }
    }

    /** Tenta BouncyCastle primeiro; ele entende o que o provider padrão recusa. */
    private static X509Certificate parseCertificate(byte[] der) {
        for (String provider : new String[] { "BC", null }) {
            try {
                CertificateFactory cf = provider == null
                        ? CertificateFactory.getInstance("X.509")
                        : CertificateFactory.getInstance("X.509", provider);
                return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
            } catch (Exception tryNext) {
                // provider ausente ou certificado que ele não entende
            }
        }
        return null;
    }

    private static Set<TrustAnchor> loadAnchorsFromKeyStore(Path truststorePath) {
        Set<TrustAnchor> anchors = new HashSet<>();
        String pw = System.getenv("ICP_BRASIL_TRUSTSTORE_PASSWORD");
        for (String type : new String[] { "JKS", "PKCS12" }) {
            try (InputStream in = Files.newInputStream(truststorePath)) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(in, pw == null ? null : pw.toCharArray());
                Enumeration<String> aliases = ks.aliases();
                while (aliases.hasMoreElements()) {
                    java.security.cert.Certificate c = ks.getCertificate(aliases.nextElement());
                    if (c instanceof X509Certificate) {
                        anchors.add(new TrustAnchor((X509Certificate) c, null));
                    }
                }
                if (!anchors.isEmpty()) {
                    return anchors;
                }
            } catch (Exception ignored) {
                // tenta o próximo tipo
            }
        }
        return anchors;
    }

    private static String issuerOf(X509Certificate cert) {
        return cert == null ? null : cert.getIssuerX500Principal().getName();
    }
}
