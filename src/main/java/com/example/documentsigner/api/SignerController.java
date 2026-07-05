package com.example.documentsigner.api;

import com.example.documentsigner.api.dto.CertificateInfo;
import com.example.documentsigner.api.dto.ErrorResponse;
import com.example.documentsigner.api.dto.PdfSignResponse;
import com.example.documentsigner.api.dto.SignResponse;
import com.example.documentsigner.api.dto.VerifyResponse;
import com.example.documentsigner.pades.dto.PdfVerificationResult;
import com.example.documentsigner.pades.dto.SignatureMetadata;
import com.example.documentsigner.pades.dto.SignaturePosition;
import com.example.documentsigner.pades.dto.TimestampConfig;
import com.example.documentsigner.pades.dto.VisualSignatureConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/v1")
// CORS travado nas origens oficiais ProcStudio (SEC #3). Endpoint recebe
// certificado + senha — não deixar wildcard. same-origin (o próprio SPA) e
// chamadas server-to-server (backend Rails) não dependem disto.
@CrossOrigin(origins = {
    "https://signer.procstudio.com.br",
    "https://hml.procstudio.com.br",
    "https://procstudio.com.br"
})
public class SignerController {

    private final SigningService signingService;

    public SignerController(SigningService signingService) {
        this.signingService = signingService;
    }

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok().body(new Object() {
            public final String status = "ok";
            public final String service = "document-signer";
            public final String timestamp = Instant.now().toString();
        });
    }

    /**
     * Get certificate information and validate password/expiry.
     * Use this endpoint to check certificate details before signing.
     */
    @PostMapping("/certificate/info")
    public ResponseEntity<?> getCertificateInfo(
            @RequestParam("certificate") MultipartFile certificate,
            @RequestParam("password") String password) {

        byte[] certBytes = null;
        try {
            certBytes = certificate.getBytes();
            CertificateInfo info = signingService.getCertificateInfo(certBytes, password);

            if (!info.isValid()) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse(info.getError(), "CERTIFICATE_ERROR"));
            }

            return ResponseEntity.ok(info);

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to read certificate file", "FILE_READ_ERROR"));
        } finally {
            com.example.documentsigner.util.Sensitive.wipe(certBytes); // zera PKCS12 (chave)
        }
    }

    /**
     * Validate certificate password and check if it's not expired.
     * Returns 200 OK if valid, or appropriate error status if not.
     */
    @PostMapping("/certificate/validate")
    public ResponseEntity<?> validateCertificate(
            @RequestParam("certificate") MultipartFile certificate,
            @RequestParam("password") String password) {

        byte[] certBytes = null;
        try {
            certBytes = certificate.getBytes();
            signingService.validateCertificate(certBytes, password);

            return ResponseEntity.ok().body(new Object() {
                public final boolean valid = true;
                public final String message = "Certificate is valid and not expired";
                public final String timestamp = Instant.now().toString();
            });

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to read certificate file", "FILE_READ_ERROR"));
        } finally {
            com.example.documentsigner.util.Sensitive.wipe(certBytes);
        }
        // Other exceptions are handled by GlobalExceptionHandler
    }

    @PostMapping("/sign")
    public ResponseEntity<?> signDocument(
            @RequestParam("document") MultipartFile document,
            @RequestParam("certificate") MultipartFile certificate,
            @RequestParam("password") String password) {

        byte[] certBytes = null;
        try {
            byte[] pdfBytes = document.getBytes();
            certBytes = certificate.getBytes();

            byte[] signature = signingService.signDocument(pdfBytes, certBytes, password);

            String originalFilename = document.getOriginalFilename();
            String outputFilename = (originalFilename != null ? originalFilename : "document") + ".p7s";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", outputFilename);
            headers.setContentLength(signature.length);

            return new ResponseEntity<>(signature, headers, HttpStatus.OK);

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to read uploaded files", "FILE_READ_ERROR"));
        } finally {
            com.example.documentsigner.util.Sensitive.wipe(certBytes);
        }
    }

    @PostMapping("/sign/json")
    public ResponseEntity<?> signDocumentJson(
            @RequestParam("document") MultipartFile document,
            @RequestParam("certificate") MultipartFile certificate,
            @RequestParam("password") String password) {

        byte[] certBytes = null;
        try {
            byte[] pdfBytes = document.getBytes();
            certBytes = certificate.getBytes();

            byte[] signature = signingService.signDocument(pdfBytes, certBytes, password);

            String originalFilename = document.getOriginalFilename();

            SignResponse response = new SignResponse(
                    true,
                    java.util.Base64.getEncoder().encodeToString(signature),
                    originalFilename,
                    Instant.now().toString()
            );

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to read uploaded files", "FILE_READ_ERROR"));
        } finally {
            com.example.documentsigner.util.Sensitive.wipe(certBytes);
        }
    }

    @PostMapping("/sign/batch")
    public ResponseEntity<?> signBatch(
            @RequestParam("documents") MultipartFile[] documents,
            @RequestParam("certificate") MultipartFile certificate,
            @RequestParam("password") String password) {

        byte[] certBytes = null;
        try {
            certBytes = certificate.getBytes();
            List<SignResponse> results = new ArrayList<>();

            for (MultipartFile document : documents) {
                try {
                    byte[] pdfBytes = document.getBytes();
                    byte[] signature = signingService.signDocument(pdfBytes, certBytes, password);

                    results.add(new SignResponse(
                            true,
                            java.util.Base64.getEncoder().encodeToString(signature),
                            document.getOriginalFilename(),
                            Instant.now().toString()
                    ));
                } catch (Exception e) {
                    results.add(new SignResponse(
                            false,
                            null,
                            document.getOriginalFilename(),
                            e.getMessage()
                    ));
                }
            }

            return ResponseEntity.ok(new Object() {
                public final boolean success = true;
                public final List<SignResponse> documents = results;
                public final int total = results.size();
                public final long signed = results.stream().filter(r -> r.success).count();
            });

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to read certificate", "FILE_READ_ERROR"));
        } finally {
            com.example.documentsigner.util.Sensitive.wipe(certBytes);
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifySignature(
            @RequestParam("document") MultipartFile document,
            @RequestParam("signature") MultipartFile signature) {

        try {
            byte[] pdfBytes = document.getBytes();
            byte[] signatureBytes = signature.getBytes();

            boolean isValid = signingService.verifySignature(signatureBytes, pdfBytes);

            return ResponseEntity.ok(new VerifyResponse(isValid, document.getOriginalFilename()));

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to read uploaded files", "FILE_READ_ERROR"));
        }
    }

    // ITI (verify/iti, sign/verified) removidos: o serviço externo do governo não
    // tem API pública de verificação — dava 502 em produção e falso-positivo em
    // staging (retornava HTML da homepage como "sucesso"). Verificação confiável é
    // a LOCAL: /verify (CAdES) e /verify/pdf (PAdES). Ver TODO-SECURITY.md #16.

    // ==================== PAdES Endpoints ====================

    /**
     * Sign PDF with PAdES format (embedded signature).
     * Returns the signed PDF file directly.
     */
    @PostMapping("/sign/pdf")
    public ResponseEntity<?> signPdfPades(
            @RequestParam("document") MultipartFile document,
            @RequestParam("certificate") MultipartFile certificate,
            @RequestParam("password") String password,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "contact", required = false) String contact,
            @RequestParam(value = "visible", defaultValue = "false") boolean visible,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "position", defaultValue = "bottom-right") String position,
            @RequestParam(value = "x", required = false) Integer x,
            @RequestParam(value = "y", required = false) Integer y,
            @RequestParam(value = "width", defaultValue = "240") int width,
            @RequestParam(value = "height", defaultValue = "102") int height,
            @RequestParam(value = "timestamp", defaultValue = "false") boolean timestamp,
            @RequestParam(value = "tsaUrl", required = false) String tsaUrl) {

        byte[] certBytes = null;
        try {
            byte[] pdfBytes = document.getBytes();
            certBytes = certificate.getBytes();

            // Build metadata
            SignatureMetadata metadata = SignatureMetadata.builder()
                .reason(reason)
                .location(location)
                .contactInfo(contact)
                .build();

            // Optional TSA config — opt-in via timestamp=true
            TimestampConfig tsConfig = timestamp ? new TimestampConfig(tsaUrl) : null;

            byte[] signedPdf;

            if (visible) {
                // Build visual config
                VisualSignatureConfig visualConfig = VisualSignatureConfig.builder()
                    .enabled(true)
                    .page(page)
                    .position(parsePosition(position))
                    .x(x)
                    .y(y)
                    .width(width)
                    .height(height)
                    .build();

                signedPdf = signingService.signDocumentPadesVisible(
                    pdfBytes, certBytes, password, metadata, visualConfig, tsConfig);
            } else {
                signedPdf = signingService.signDocumentPades(
                    pdfBytes, certBytes, password, metadata, tsConfig);
            }

            String originalFilename = document.getOriginalFilename();
            String outputFilename = generateSignedFilename(originalFilename);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", outputFilename);
            headers.setContentLength(signedPdf.length);

            return new ResponseEntity<>(signedPdf, headers, HttpStatus.OK);

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to read uploaded files", "FILE_READ_ERROR"));
        } finally {
            com.example.documentsigner.util.Sensitive.wipe(certBytes);
        }
    }

    /**
     * Sign PDF with PAdES format and return as JSON with base64.
     */
    @PostMapping("/sign/pdf/json")
    public ResponseEntity<?> signPdfPadesJson(
            @RequestParam("document") MultipartFile document,
            @RequestParam("certificate") MultipartFile certificate,
            @RequestParam("password") String password,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "contact", required = false) String contact,
            @RequestParam(value = "visible", defaultValue = "false") boolean visible,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "position", defaultValue = "bottom-right") String position,
            @RequestParam(value = "x", required = false) Integer x,
            @RequestParam(value = "y", required = false) Integer y,
            @RequestParam(value = "width", defaultValue = "240") int width,
            @RequestParam(value = "height", defaultValue = "102") int height,
            @RequestParam(value = "timestamp", defaultValue = "false") boolean timestamp,
            @RequestParam(value = "tsaUrl", required = false) String tsaUrl) {

        byte[] certBytes = null;
        try {
            byte[] pdfBytes = document.getBytes();
            certBytes = certificate.getBytes();

            // Build metadata
            SignatureMetadata metadata = SignatureMetadata.builder()
                .reason(reason)
                .location(location)
                .contactInfo(contact)
                .build();

            TimestampConfig tsConfig = timestamp ? new TimestampConfig(tsaUrl) : null;

            byte[] signedPdf;

            if (visible) {
                VisualSignatureConfig visualConfig = VisualSignatureConfig.builder()
                    .enabled(true)
                    .page(page)
                    .position(parsePosition(position))
                    .x(x)
                    .y(y)
                    .width(width)
                    .height(height)
                    .build();

                signedPdf = signingService.signDocumentPadesVisible(
                    pdfBytes, certBytes, password, metadata, visualConfig, tsConfig);
            } else {
                signedPdf = signingService.signDocumentPades(
                    pdfBytes, certBytes, password, metadata, tsConfig);
            }

            String originalFilename = document.getOriginalFilename();
            String outputFilename = generateSignedFilename(originalFilename);

            PdfSignResponse.SignatureInfo sigInfo = new PdfSignResponse.SignatureInfo(
                null, // signer name extracted from cert
                Instant.now().toString(),
                reason,
                visible
            );

            PdfSignResponse response = PdfSignResponse.success(
                java.util.Base64.getEncoder().encodeToString(signedPdf),
                outputFilename,
                originalFilename,
                sigInfo,
                Instant.now().toString()
            );

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to read uploaded files", "FILE_READ_ERROR"));
        } finally {
            com.example.documentsigner.util.Sensitive.wipe(certBytes);
        }
    }

    /**
     * Batch sign multiple PDFs with PAdES format.
     * Returns a ZIP archive containing all signed PDFs.
     */
    @PostMapping("/sign/pdf/batch")
    public ResponseEntity<?> signPdfPadesBatch(
            @RequestParam("documents") MultipartFile[] documents,
            @RequestParam("certificate") MultipartFile certificate,
            @RequestParam("password") String password,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "contact", required = false) String contact,
            @RequestParam(value = "visible", defaultValue = "false") boolean visible,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "position", defaultValue = "bottom-right") String position,
            @RequestParam(value = "width", defaultValue = "240") int width,
            @RequestParam(value = "height", defaultValue = "102") int height) {

        byte[] certBytes = null;
        try {
            certBytes = certificate.getBytes();

            SignatureMetadata metadata = SignatureMetadata.builder()
                .reason(reason)
                .location(location)
                .contactInfo(contact)
                .build();

            VisualSignatureConfig visualConfig = null;
            if (visible) {
                visualConfig = VisualSignatureConfig.builder()
                    .enabled(true)
                    .page(page)
                    .position(parsePosition(position))
                    .width(width)
                    .height(height)
                    .build();
            }

            ByteArrayOutputStream zipOutput = new ByteArrayOutputStream();
            ZipOutputStream zipStream = new ZipOutputStream(zipOutput);

            int successCount = 0;
            int failCount = 0;

            for (MultipartFile document : documents) {
                try {
                    byte[] pdfBytes = document.getBytes();
                    byte[] signedPdf;

                    if (visualConfig != null) {
                        signedPdf = signingService.signDocumentPadesVisible(
                            pdfBytes, certBytes, password, metadata, visualConfig);
                    } else {
                        signedPdf = signingService.signDocumentPades(pdfBytes, certBytes, password, metadata);
                    }

                    String outputFilename = generateSignedFilename(document.getOriginalFilename());
                    zipStream.putNextEntry(new ZipEntry(outputFilename));
                    zipStream.write(signedPdf);
                    zipStream.closeEntry();
                    successCount++;

                } catch (Exception e) {
                    // Add error log file for failed documents
                    String errorFilename = document.getOriginalFilename() + ".error.txt";
                    zipStream.putNextEntry(new ZipEntry(errorFilename));
                    zipStream.write(("Error: " + e.getMessage()).getBytes());
                    zipStream.closeEntry();
                    failCount++;
                }
            }

            zipStream.close();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.setContentDispositionFormData("attachment", "signed_documents.zip");
            headers.set("X-Signed-Count", String.valueOf(successCount));
            headers.set("X-Failed-Count", String.valueOf(failCount));

            return new ResponseEntity<>(zipOutput.toByteArray(), headers, HttpStatus.OK);

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to process batch signing", "BATCH_SIGN_ERROR"));
        } finally {
            com.example.documentsigner.util.Sensitive.wipe(certBytes);
        }
    }

    /**
     * Verify embedded PDF signature (PAdES).
     */
    @PostMapping("/verify/pdf")
    public ResponseEntity<?> verifyPdfSignature(
            @RequestParam("document") MultipartFile document) {

        try {
            byte[] pdfBytes = document.getBytes();
            PdfVerificationResult result = signingService.verifyPdfSignature(pdfBytes);

            java.util.List<Object> sigDtos = new java.util.ArrayList<>();
            for (com.example.documentsigner.pades.dto.SignatureDetails s : result.getSignatures()) {
                sigDtos.add(toSignatureDto(s));
            }

            final java.util.List<Object> sigs = sigDtos;
            // Legacy v1 contract: `signature` (singular) = most recent signer.
            // Kept as an alias for backwards-compat with the Svelte frontend and
            // existing Bruno collections. New consumers should read `signatures[]`.
            final Object primarySignature = sigs.isEmpty() ? null : sigs.get(sigs.size() - 1);
            return ResponseEntity.ok(new Object() {
                public final boolean valid = result.isValid();
                public final int totalSignatures = result.getTotalSignatures();
                public final java.util.List<Object> signatures = sigs;
                public final Object signature = primarySignature; // legacy alias
                public final String filename = document.getOriginalFilename();
                public final String details = result.getDetails();
                public final String timestamp = Instant.now().toString();
            });

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Failed to read uploaded file", "FILE_READ_ERROR"));
        }
    }

    private Object toSignatureDto(com.example.documentsigner.pades.dto.SignatureDetails s) {
        final com.example.documentsigner.pades.dto.TsaInfo tsaSrc = s.getTsa();
        final com.example.documentsigner.pades.dto.RevocationStatus revSrc = s.getRevocationStatus();
        final com.example.documentsigner.pades.dto.CertificateType certType = s.getCertificateType();
        final String certTypeName = certType != null ? certType.name() : null;
        final String certTypeLabel = certType != null ? certType.getLabel() : null;
        final String signerCpf = s.getCpf();
        return new Object() {
            public final int index = s.getIndex();
            public final String signerName = s.getSignerName();
            public final String cpf = signerCpf;                       // null p/ gov.br
            public final String certificateType = certTypeName;        // ICP_BRASIL | GOV_BR | OTHER
            public final String certificateTypeLabel = certTypeLabel;  // rótulo legível
            public final String signingTime = s.getSigningTime() != null
                ? s.getSigningTime().toString() : null;
            public final String reason = s.getReason();
            public final boolean valid = s.isValid();
            public final boolean integrityValid = s.isIntegrityValid();
            public final boolean certificateValid = s.isCertificateValid();
            public final boolean coversWholeDocument = s.isCoversWholeDocument();
            public final Object tsa = tsaSrc == null ? null : new Object() {
                public final String timestamp = tsaSrc.getTimestamp() != null
                    ? tsaSrc.getTimestamp().toString() : null;
                public final String tsaName = tsaSrc.getTsaName();
                public final boolean tokenValid = tsaSrc.isTokenValid();
            };
            public final Object revocation = revSrc == null ? null : new Object() {
                public final String state = revSrc.getState().name();
                public final String details = revSrc.getDetails();
                public final String checkedAt = revSrc.getCheckedAt() != null
                    ? revSrc.getCheckedAt().toString() : null;
                public final String revokedAt = revSrc.getRevokedAt() != null
                    ? revSrc.getRevokedAt().toString() : null;
            };
            public final String details = s.getDetails();
        };
    }

    /**
     * Sign PDF with PAdES and verify locally.
     */
    @PostMapping("/sign/pdf/verified")
    public ResponseEntity<?> signPdfPadesAndVerify(
            @RequestParam("document") MultipartFile document,
            @RequestParam("certificate") MultipartFile certificate,
            @RequestParam("password") String password,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "location", required = false) String location) {

        byte[] certBytes = null;
        try {
            byte[] pdfBytes = document.getBytes();
            certBytes = certificate.getBytes();
            String docFilename = document.getOriginalFilename() != null
                ? document.getOriginalFilename()
                : "document.pdf";

            SigningService.PadesSignAndVerifyResult result = signingService.signPadesAndVerify(
                pdfBytes,
                certBytes,
                password
            );

            final PdfVerificationResult verificationResult = result.getVerificationResult();
            final String signedFilename = generateSignedFilename(docFilename);

            return ResponseEntity.ok(new Object() {
                public final boolean success = true;
                public final String signedPdfBase64 = java.util.Base64.getEncoder()
                    .encodeToString(result.getSignedPdf());
                public final String filename = signedFilename;
                public final Object verification = new Object() {
                    public final boolean valid = verificationResult.isValid();
                    public final String signerName = verificationResult.getSignerName();
                    public final boolean integrityValid = verificationResult.isIntegrityValid();
                    public final String details = verificationResult.getDetails();
                };
                public final String timestamp = Instant.now().toString();
            });

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to sign and verify", "SIGN_VERIFY_ERROR"));
        } finally {
            com.example.documentsigner.util.Sensitive.wipe(certBytes);
        }
    }

    // ==================== Helper Methods ====================

    private SignaturePosition parsePosition(String position) {
        if (position == null) {
            return SignaturePosition.BOTTOM_RIGHT;
        }
        switch (position.toLowerCase().replace("-", "_")) {
            case "bottom_left":
            case "bottomleft":
                return SignaturePosition.BOTTOM_LEFT;
            case "top_left":
            case "topleft":
                return SignaturePosition.TOP_LEFT;
            case "top_right":
            case "topright":
                return SignaturePosition.TOP_RIGHT;
            case "custom":
                return SignaturePosition.CUSTOM;
            case "bottom_right":
            case "bottomright":
            default:
                return SignaturePosition.BOTTOM_RIGHT;
        }
    }

    private String generateSignedFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isEmpty()) {
            return "document_signed.pdf";
        }
        if (originalFilename.toLowerCase().endsWith(".pdf")) {
            return originalFilename.substring(0, originalFilename.length() - 4) + "_signed.pdf";
        }
        return originalFilename + "_signed.pdf";
    }
}
