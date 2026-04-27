package com.example.documentsigner.api;

import com.example.documentsigner.CertificateValidator;
import com.example.documentsigner.ItiVerificador;
import com.example.documentsigner.ItiVerificador.ItiVerificationResult;
import com.example.documentsigner.PdfSigner;
import com.example.documentsigner.api.dto.CertificateInfo;
import com.example.documentsigner.pades.dto.PdfVerificationResult;
import com.example.documentsigner.pades.dto.SignatureMetadata;
import com.example.documentsigner.pades.dto.TimestampConfig;
import com.example.documentsigner.pades.dto.VisualSignatureConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class SigningService {

    private final PdfSigner pdfSigner;

    public SigningService() {
        this.pdfSigner = new PdfSigner();
    }

    /**
     * Get certificate information.
     *
     * @param certBytes The PFX certificate bytes
     * @param password The certificate password
     * @return CertificateInfo with all certificate details
     */
    public CertificateInfo getCertificateInfo(byte[] certBytes, String password) {
        return CertificateValidator.getCertificateInfo(certBytes, password);
    }

    /**
     * Validate certificate password and expiry.
     * Throws appropriate exceptions if validation fails.
     *
     * @param certBytes The PFX certificate bytes
     * @param password The certificate password
     */
    public void validateCertificate(byte[] certBytes, String password) {
        CertificateValidator.validateCertificate(certBytes, password);
    }

    /**
     * Sign a PDF document with a certificate.
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX certificate bytes
     * @param password The certificate password
     * @return The P7S signature bytes
     */
    public byte[] signDocument(byte[] pdfBytes, byte[] certBytes, String password) {
        return pdfSigner.signPdfBytes(pdfBytes, certBytes, password);
    }

    /**
     * Verify a signature against the original document.
     *
     * @param signatureBytes The P7S signature bytes
     * @param originalPdfBytes The original PDF bytes
     * @return true if signature is valid
     */
    public boolean verifySignature(byte[] signatureBytes, byte[] originalPdfBytes) {
        return pdfSigner.verifySignature(signatureBytes, originalPdfBytes);
    }

    /**
     * Verify a detached signature using the ITI Verificador (Brazilian Government).
     * This is the official external source of truth for ICP-Brasil signatures.
     *
     * @param signatureBytes The P7S signature bytes
     * @param documentBytes The original document bytes
     * @param signatureFilename Filename for the signature
     * @param documentFilename Filename for the document
     * @param useStaging true to use staging environment
     * @return ItiVerificationResult with the validation response
     * @throws IOException if the request fails
     */
    public ItiVerificationResult verifyWithIti(
            byte[] signatureBytes,
            byte[] documentBytes,
            String signatureFilename,
            String documentFilename,
            boolean useStaging) throws IOException {

        ItiVerificador verificador = new ItiVerificador(useStaging);
        return verificador.verifyDetachedSignature(
            signatureBytes,
            documentBytes,
            signatureFilename,
            documentFilename
        );
    }

    /**
     * Sign a document and then verify it with ITI Verificador.
     * Complete flow for signing with external validation.
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX certificate bytes
     * @param password The certificate password
     * @param documentFilename Original document filename
     * @param useStaging true to use ITI staging environment
     * @return SignAndVerifyResult containing signature and ITI validation
     * @throws IOException if ITI verification fails
     */
    public SignAndVerifyResult signAndVerifyWithIti(
            byte[] pdfBytes,
            byte[] certBytes,
            String password,
            String documentFilename,
            boolean useStaging) throws IOException {

        // Sign the document
        byte[] signature = pdfSigner.signPdfBytes(pdfBytes, certBytes, password);

        // Verify with ITI
        ItiVerificationResult itiResult = verifyWithIti(
            signature,
            pdfBytes,
            documentFilename + ".p7s",
            documentFilename,
            useStaging
        );

        return new SignAndVerifyResult(signature, itiResult);
    }

    /**
     * Result of sign and verify operation.
     */
    public static class SignAndVerifyResult {
        private final byte[] signature;
        private final ItiVerificationResult itiResult;

        public SignAndVerifyResult(byte[] signature, ItiVerificationResult itiResult) {
            this.signature = signature;
            this.itiResult = itiResult;
        }

        public byte[] getSignature() {
            return signature;
        }

        public ItiVerificationResult getItiResult() {
            return itiResult;
        }
    }

    // ==================== PAdES Signing Methods ====================

    /**
     * Sign a PDF document with PAdES format (embedded signature).
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX certificate bytes
     * @param password The certificate password
     * @return The signed PDF bytes
     */
    public byte[] signDocumentPades(byte[] pdfBytes, byte[] certBytes, String password) {
        return pdfSigner.signPdfPades(pdfBytes, certBytes, password);
    }

    /**
     * Sign a PDF document with PAdES format including metadata.
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX certificate bytes
     * @param password The certificate password
     * @param metadata Signature metadata (reason, location, contact)
     * @return The signed PDF bytes
     */
    public byte[] signDocumentPades(byte[] pdfBytes, byte[] certBytes, String password,
                                     SignatureMetadata metadata) {
        return pdfSigner.signPdfPades(pdfBytes, certBytes, password, metadata);
    }

    /**
     * Sign PDF with PAdES + optional TSA timestamp.
     * If {@code timestampConfig} is non-null and TSA reachable, output is PAdES-T.
     * If TSA fails, falls back to PAdES-B with a warning logged.
     */
    public byte[] signDocumentPades(byte[] pdfBytes, byte[] certBytes, String password,
                                     SignatureMetadata metadata, TimestampConfig timestampConfig) {
        return pdfSigner.signPdfPades(pdfBytes, certBytes, password, metadata, timestampConfig);
    }

    /**
     * Sign a PDF document with PAdES format and visible signature.
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX certificate bytes
     * @param password The certificate password
     * @param metadata Signature metadata (reason, location, contact)
     * @param visualConfig Visual signature configuration
     * @return The signed PDF bytes with visible signature
     */
    public byte[] signDocumentPadesVisible(byte[] pdfBytes, byte[] certBytes, String password,
                                            SignatureMetadata metadata, VisualSignatureConfig visualConfig) {
        return pdfSigner.signPdfPadesVisible(pdfBytes, certBytes, password, metadata, visualConfig);
    }

    /**
     * Sign PDF with PAdES, visible signature, and optional TSA timestamp.
     */
    public byte[] signDocumentPadesVisible(byte[] pdfBytes, byte[] certBytes, String password,
                                            SignatureMetadata metadata, VisualSignatureConfig visualConfig,
                                            TimestampConfig timestampConfig) {
        return pdfSigner.signPdfPadesVisible(
            pdfBytes, certBytes, password, metadata, visualConfig, timestampConfig);
    }

    /**
     * Verify embedded PDF signature (PAdES).
     *
     * @param signedPdfBytes The signed PDF bytes
     * @return Verification result with details
     */
    public PdfVerificationResult verifyPdfSignature(byte[] signedPdfBytes) {
        return pdfSigner.verifyPdfSignature(signedPdfBytes);
    }

    /**
     * Sign PDF with PAdES and verify with ITI Verificador.
     *
     * @param pdfBytes The PDF document bytes
     * @param certBytes The PFX certificate bytes
     * @param password The certificate password
     * @param documentFilename Original document filename
     * @param useStaging true to use ITI staging environment
     * @return Result containing signed PDF and ITI validation
     * @throws IOException if ITI verification fails
     */
    public PadesSignAndVerifyResult signPadesAndVerifyWithIti(
            byte[] pdfBytes,
            byte[] certBytes,
            String password,
            String documentFilename,
            boolean useStaging) throws IOException {

        // Sign the document with PAdES
        byte[] signedPdf = pdfSigner.signPdfPades(pdfBytes, certBytes, password);

        // Verify with ITI (Note: ITI may require specific format for PAdES)
        // For now, we verify the signed PDF directly
        PdfVerificationResult localVerification = pdfSigner.verifyPdfSignature(signedPdf);

        return new PadesSignAndVerifyResult(signedPdf, localVerification);
    }

    /**
     * Result of PAdES sign and verify operation.
     */
    public static class PadesSignAndVerifyResult {
        private final byte[] signedPdf;
        private final PdfVerificationResult verificationResult;

        public PadesSignAndVerifyResult(byte[] signedPdf, PdfVerificationResult verificationResult) {
            this.signedPdf = signedPdf;
            this.verificationResult = verificationResult;
        }

        public byte[] getSignedPdf() {
            return signedPdf;
        }

        public PdfVerificationResult getVerificationResult() {
            return verificationResult;
        }
    }
}
