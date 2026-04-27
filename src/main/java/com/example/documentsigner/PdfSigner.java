package com.example.documentsigner;

import com.example.documentsigner.exception.ExpiredCertificateException;
import com.example.documentsigner.exception.InvalidCertificateException;
import com.example.documentsigner.exception.InvalidDocumentException;
import com.example.documentsigner.exception.InvalidPasswordException;
import com.example.documentsigner.exception.SigningException;
import com.example.documentsigner.pades.PadesSignerService;
import com.example.documentsigner.pades.dto.PdfVerificationResult;
import com.example.documentsigner.pades.dto.SignatureMetadata;
import com.example.documentsigner.pades.dto.TimestampConfig;
import com.example.documentsigner.pades.dto.VisualSignatureConfig;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;

public class PdfSigner {

    /**
     * Signature format enum for selecting output type.
     */
    public enum SignatureFormat {
        /** PAdES - Embedded PDF signature (single signed PDF file) */
        PADES,
        /** CMS/PKCS#7 - Detached signature (.p7s file) */
        CMS
    }

    private final DocumentSigner documentSigner;
    private final PadesSignerService padesSignerService;

    public PdfSigner() {
        this.documentSigner = new DocumentSigner();
        this.padesSignerService = new PadesSignerService();
    }

    /**
     * Sign a PDF file and save the signature to a .p7s file.
     * (Original method for GUI compatibility)
     */
    public void signPdf(String inputPdf, String outputP7s, String pfxPath, String pfxPassword) {
        try {
            PDDocument document = PDDocument.load(new File(inputPdf));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            document.close();

            byte[] signedData = documentSigner.signDocument(baos.toByteArray(), pfxPath, pfxPassword);

            FileOutputStream fos = new FileOutputStream(outputP7s);
            fos.write(signedData);
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sign PDF bytes using certificate bytes.
     * (New method for API usage - no file paths needed)
     *
     * @param pdfBytes The PDF document as byte array
     * @param certBytes The PFX/PKCS12 certificate as byte array
     * @param password The certificate password
     * @return The P7S signature as byte array
     * @throws SigningException if signing fails
     */
    public byte[] signPdfBytes(byte[] pdfBytes, byte[] certBytes, String password) {
        // Validate inputs
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new InvalidDocumentException("PDF document is empty or null");
        }
        if (certBytes == null || certBytes.length == 0) {
            throw new InvalidCertificateException("Certificate is empty or null");
        }
        if (password == null || password.isEmpty()) {
            throw new InvalidPasswordException("Password is required");
        }

        try {
            // Validate PDF format
            PDDocument document = PDDocument.load(pdfBytes);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            document.close();

            // Validate certificate format and password
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            try {
                keystore.load(new ByteArrayInputStream(certBytes), password.toCharArray());
            } catch (java.io.IOException e) {
                if (e.getCause() instanceof java.security.UnrecoverableKeyException) {
                    throw new InvalidPasswordException("Incorrect certificate password", e);
                }
                throw new InvalidCertificateException("Invalid certificate format", e);
            }

            // Check certificate expiry
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

            // Sign the document
            return documentSigner.signDocumentWithCertBytes(baos.toByteArray(), certBytes, password);

        } catch (InvalidDocumentException | InvalidCertificateException | InvalidPasswordException | ExpiredCertificateException e) {
            throw e;
        } catch (Exception e) {
            throw new SigningException("Failed to sign document: " + e.getMessage(), e);
        }
    }

    /**
     * Verify a signature against the original document.
     *
     * @param signatureBytes The P7S signature bytes
     * @param originalPdfBytes The original PDF bytes
     * @return true if signature is valid
     * @throws SigningException if verification fails
     */
    public boolean verifySignature(byte[] signatureBytes, byte[] originalPdfBytes) {
        try {
            // Load and normalize the PDF
            PDDocument document = PDDocument.load(originalPdfBytes);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            document.close();

            return documentSigner.verifySignature(signatureBytes, baos.toByteArray());
        } catch (Exception e) {
            throw new SigningException("Failed to verify signature: " + e.getMessage(), e);
        }
    }

    // ==================== PAdES Signing Methods ====================

    /**
     * Sign PDF using PAdES format (embedded signature).
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX/PKCS12 certificate bytes
     * @param password The certificate password
     * @return Signed PDF bytes with embedded signature
     * @throws SigningException if signing fails
     */
    public byte[] signPdfPades(byte[] pdfBytes, byte[] certBytes, String password) {
        return padesSignerService.signPdf(pdfBytes, certBytes, password, null);
    }

    /**
     * Sign PDF using PAdES format with metadata.
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX/PKCS12 certificate bytes
     * @param password The certificate password
     * @param metadata Signature metadata (reason, location, contact)
     * @return Signed PDF bytes with embedded signature
     * @throws SigningException if signing fails
     */
    public byte[] signPdfPades(byte[] pdfBytes, byte[] certBytes, String password,
                                SignatureMetadata metadata) {
        return padesSignerService.signPdf(pdfBytes, certBytes, password, metadata);
    }

    /**
     * Sign PDF using PAdES format with optional TSA timestamp (PAdES-T when {@code timestampConfig} is non-null).
     */
    public byte[] signPdfPades(byte[] pdfBytes, byte[] certBytes, String password,
                                SignatureMetadata metadata, TimestampConfig timestampConfig) {
        return padesSignerService.signPdf(pdfBytes, certBytes, password, metadata, timestampConfig);
    }

    /**
     * Sign PDF using PAdES format with visible signature.
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX/PKCS12 certificate bytes
     * @param password The certificate password
     * @param metadata Signature metadata (reason, location, contact)
     * @param visualConfig Visual signature configuration
     * @return Signed PDF bytes with embedded visible signature
     * @throws SigningException if signing fails
     */
    public byte[] signPdfPadesVisible(byte[] pdfBytes, byte[] certBytes, String password,
                                       SignatureMetadata metadata, VisualSignatureConfig visualConfig) {
        return padesSignerService.signPdfVisible(pdfBytes, certBytes, password, metadata, visualConfig);
    }

    /**
     * Sign PDF using PAdES with visible signature and optional TSA timestamp.
     */
    public byte[] signPdfPadesVisible(byte[] pdfBytes, byte[] certBytes, String password,
                                       SignatureMetadata metadata, VisualSignatureConfig visualConfig,
                                       TimestampConfig timestampConfig) {
        return padesSignerService.signPdfVisible(
            pdfBytes, certBytes, password, metadata, visualConfig, timestampConfig);
    }

    /**
     * Sign PDF with format selection.
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX/PKCS12 certificate bytes
     * @param password The certificate password
     * @param format Signature format (PADES or CMS)
     * @param metadata Signature metadata (used for PAdES only)
     * @param visualConfig Visual signature config (used for PAdES only)
     * @return For PADES: signed PDF bytes. For CMS: P7S signature bytes.
     * @throws SigningException if signing fails
     */
    public byte[] sign(byte[] pdfBytes, byte[] certBytes, String password,
                       SignatureFormat format, SignatureMetadata metadata,
                       VisualSignatureConfig visualConfig) {
        if (format == SignatureFormat.PADES) {
            if (visualConfig != null && visualConfig.isEnabled()) {
                return signPdfPadesVisible(pdfBytes, certBytes, password, metadata, visualConfig);
            } else {
                return signPdfPades(pdfBytes, certBytes, password, metadata);
            }
        } else {
            // CMS format - existing behavior
            return signPdfBytes(pdfBytes, certBytes, password);
        }
    }

    /**
     * Verify embedded PDF signature (PAdES).
     *
     * @param signedPdfBytes The signed PDF bytes
     * @return Verification result
     * @throws SigningException if verification fails
     */
    public PdfVerificationResult verifyPdfSignature(byte[] signedPdfBytes) {
        return padesSignerService.verifyPdfSignature(signedPdfBytes);
    }

    /**
     * Get the PAdES signer service for advanced operations.
     */
    public PadesSignerService getPadesSignerService() {
        return padesSignerService;
    }
}
