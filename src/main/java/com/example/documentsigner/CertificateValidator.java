package com.example.documentsigner;

import com.example.documentsigner.api.dto.CertificateInfo;
import com.example.documentsigner.exception.ExpiredCertificateException;
import com.example.documentsigner.exception.InvalidCertificateException;
import com.example.documentsigner.exception.InvalidPasswordException;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for validating A1 certificates and extracting their details.
 */
public class CertificateValidator {

    /**
     * Validate certificate password and check if it can be loaded.
     *
     * @param certPath Path to the certificate file
     * @param password Certificate password
     * @return true if password is valid
     * @throws InvalidPasswordException if password is incorrect
     * @throws InvalidCertificateException if certificate cannot be loaded
     */
    public static boolean validatePassword(String certPath, String password) {
        try (FileInputStream fis = new FileInputStream(certPath)) {
            return validatePassword(fis, password);
        } catch (java.io.FileNotFoundException e) {
            throw new InvalidCertificateException("Certificate file not found: " + certPath);
        } catch (java.io.IOException e) {
            throw new InvalidCertificateException("Error reading certificate file: " + e.getMessage());
        }
    }

    /**
     * Validate certificate password from bytes.
     *
     * @param certBytes Certificate bytes
     * @param password Certificate password
     * @return true if password is valid
     * @throws InvalidPasswordException if password is incorrect
     * @throws InvalidCertificateException if certificate cannot be loaded
     */
    public static boolean validatePassword(byte[] certBytes, String password) {
        return validatePassword(new ByteArrayInputStream(certBytes), password);
    }

    /**
     * Validate certificate password from input stream.
     */
    private static boolean validatePassword(InputStream certStream, String password) {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            char[] pw = password.toCharArray();
            keystore.load(certStream, pw);

            // Try to get the private key to verify password works for key access
            String alias = keystore.aliases().nextElement();
            keystore.getKey(alias, pw);
            com.example.documentsigner.util.Sensitive.wipe(pw);

            return true;
        } catch (java.security.UnrecoverableKeyException e) {
            throw new InvalidPasswordException("Incorrect certificate password");
        } catch (java.io.IOException e) {
            if (e.getCause() instanceof java.security.UnrecoverableKeyException ||
                e.getMessage().contains("password")) {
                throw new InvalidPasswordException("Incorrect certificate password");
            }
            throw new InvalidCertificateException("Invalid certificate format: " + e.getMessage());
        } catch (Exception e) {
            throw new InvalidCertificateException("Error loading certificate: " + e.getMessage());
        }
    }

    /**
     * Check if certificate is expired.
     *
     * @param certPath Path to the certificate file
     * @param password Certificate password
     * @throws ExpiredCertificateException if certificate is expired
     * @throws InvalidPasswordException if password is incorrect
     * @throws InvalidCertificateException if certificate cannot be loaded
     */
    public static void checkExpiry(String certPath, String password) {
        try (FileInputStream fis = new FileInputStream(certPath)) {
            checkExpiry(fis, password);
        } catch (ExpiredCertificateException | InvalidPasswordException | InvalidCertificateException e) {
            throw e;
        } catch (java.io.FileNotFoundException e) {
            throw new InvalidCertificateException("Certificate file not found: " + certPath);
        } catch (java.io.IOException e) {
            throw new InvalidCertificateException("Error reading certificate file: " + e.getMessage());
        }
    }

    /**
     * Check if certificate is expired from bytes.
     */
    public static void checkExpiry(byte[] certBytes, String password) {
        checkExpiry(new ByteArrayInputStream(certBytes), password);
    }

    /**
     * Check if certificate is expired from input stream.
     */
    private static void checkExpiry(InputStream certStream, String password) {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            char[] pw = password.toCharArray();
            keystore.load(certStream, pw);
            com.example.documentsigner.util.Sensitive.wipe(pw);

            String alias = keystore.aliases().nextElement();
            X509Certificate cert = (X509Certificate) keystore.getCertificate(alias);

            Date now = new Date();
            if (now.after(cert.getNotAfter())) {
                throw new ExpiredCertificateException(
                    "Certificate expired on " + cert.getNotAfter(),
                    cert.getNotAfter()
                );
            }
            if (now.before(cert.getNotBefore())) {
                throw new InvalidCertificateException(
                    "Certificate is not yet valid. Valid from: " + cert.getNotBefore()
                );
            }
        } catch (ExpiredCertificateException | InvalidCertificateException e) {
            throw e;
        } catch (java.io.IOException e) {
            if (e.getCause() instanceof java.security.UnrecoverableKeyException ||
                e.getMessage().contains("password")) {
                throw new InvalidPasswordException("Incorrect certificate password");
            }
            throw new InvalidCertificateException("Invalid certificate format: " + e.getMessage());
        } catch (Exception e) {
            throw new InvalidCertificateException("Error loading certificate: " + e.getMessage());
        }
    }

    /**
     * Get certificate details.
     *
     * @param certPath Path to the certificate file
     * @param password Certificate password
     * @return CertificateInfo with all details
     */
    public static CertificateInfo getCertificateInfo(String certPath, String password) {
        try (FileInputStream fis = new FileInputStream(certPath)) {
            return getCertificateInfo(fis, password);
        } catch (java.io.FileNotFoundException e) {
            return new CertificateInfo(false, "Certificate file not found: " + certPath);
        } catch (java.io.IOException e) {
            return new CertificateInfo(false, "Error reading certificate file: " + e.getMessage());
        }
    }

    /**
     * Get certificate details from bytes.
     */
    public static CertificateInfo getCertificateInfo(byte[] certBytes, String password) {
        return getCertificateInfo(new ByteArrayInputStream(certBytes), password);
    }

    /**
     * Get certificate details from input stream.
     */
    private static CertificateInfo getCertificateInfo(InputStream certStream, String password) {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            char[] pw = password.toCharArray();
            keystore.load(certStream, pw);
            com.example.documentsigner.util.Sensitive.wipe(pw);

            String alias = keystore.aliases().nextElement();
            X509Certificate cert = (X509Certificate) keystore.getCertificate(alias);

            // Extract subject details
            String subject = cert.getSubjectX500Principal().getName();
            String commonName = extractCN(subject);
            String issuer = cert.getIssuerX500Principal().getName();
            String serialNumber = cert.getSerialNumber().toString(16).toUpperCase();

            Date notBefore = cert.getNotBefore();
            Date notAfter = cert.getNotAfter();
            Date now = new Date();

            boolean expired = now.after(notAfter);
            long daysUntilExpiry = TimeUnit.MILLISECONDS.toDays(notAfter.getTime() - now.getTime());

            String algorithm = cert.getSigAlgName();

            CertificateInfo info = new CertificateInfo(
                true,
                subject,
                commonName,
                issuer,
                serialNumber,
                notBefore,
                notAfter,
                expired,
                daysUntilExpiry,
                algorithm
            );

            // Origem DECLARADA pelo certificado: ICP-Brasil (A1/A3) / gov.br / outro.
            // Atenção: isto é auto-declaração (OID/subject/issuer), não prova.
            com.example.documentsigner.pades.dto.CertificateType type =
                com.example.documentsigner.pades.CertificateTypeDetector.detect(cert);
            info.setCertificateType(type.name());
            info.setCertificateTypeLabel(type.getLabel());

            // Confiança CONFERIDA: a cadeia fecha contra o truststore ICP-Brasil?
            // Eixo separado do de validade — um certificado dentro da validade
            // pode ser autoassinado. Autoassinado é pego sem truststore.
            com.example.documentsigner.pki.ChainVerification chain =
                com.example.documentsigner.pki.CertificateChainVerifier.verify(cert, chainOf(keystore, alias));
            info.setChainStatus(chain.getChainStatus());
            info.setChainReason(chain.getChainReason());
            info.setChainIssuer(chain.getChainIssuer());

            return info;

        } catch (java.io.IOException e) {
            if (e.getCause() instanceof java.security.UnrecoverableKeyException ||
                e.getMessage().contains("password")) {
                return new CertificateInfo(false, "Incorrect certificate password");
            }
            return new CertificateInfo(false, "Invalid certificate format: " + e.getMessage());
        } catch (Exception e) {
            return new CertificateInfo(false, "Error loading certificate: " + e.getMessage());
        }
    }

    /** Cadeia embarcada no PKCS12, para a verificação PKIX. */
    private static java.util.List<X509Certificate> chainOf(KeyStore keystore, String alias) {
        java.util.List<X509Certificate> out = new java.util.ArrayList<>();
        try {
            java.security.cert.Certificate[] raw = keystore.getCertificateChain(alias);
            if (raw != null) {
                for (java.security.cert.Certificate c : raw) {
                    if (c instanceof X509Certificate) {
                        out.add((X509Certificate) c);
                    }
                }
            }
        } catch (Exception ignored) {
            // cadeia ausente não impede a resposta: o verificador devolve
            // incomplete/unverified em vez de estourar o upload.
        }
        return out;
    }

    /**
     * Full validation: password, expiry, and certificate validity.
     *
     * @param certPath Path to the certificate file
     * @param password Certificate password
     * @throws InvalidPasswordException if password is incorrect
     * @throws ExpiredCertificateException if certificate is expired
     * @throws InvalidCertificateException if certificate is invalid
     */
    public static void validateCertificate(String certPath, String password) {
        validatePassword(certPath, password);
        checkExpiry(certPath, password);
    }

    /**
     * Full validation from bytes.
     */
    public static void validateCertificate(byte[] certBytes, String password) {
        validatePassword(certBytes, password);
        checkExpiry(certBytes, password);
    }

    /**
     * Extract Common Name (CN) from X.500 distinguished name.
     */
    private static String extractCN(String dn) {
        Pattern pattern = Pattern.compile("CN=([^,]+)");
        Matcher matcher = pattern.matcher(dn);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return dn;
    }
}
