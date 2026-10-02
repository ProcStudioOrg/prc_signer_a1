package com.example.documentsigner.api;

import com.example.documentsigner.exception.InvalidDocumentException;
import com.example.documentsigner.pades.dto.PdfVerificationResult;
import com.example.documentsigner.pades.dto.SignatureMetadata;
import com.example.documentsigner.usage.UsageTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SignerControllerUsageTest {

    private SigningService signingService;
    private UsageTracker tracker;
    private SignerController controller;
    private MockHttpServletRequest request;
    private MockMultipartFile document;
    private MockMultipartFile certificate;

    @BeforeEach
    void setUp() {
        signingService = mock(SigningService.class);
        tracker = mock(UsageTracker.class);
        controller = new SignerController(signingService, tracker);
        request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        document = new MockMultipartFile("document", "doc.pdf", "application/pdf", new byte[] {1});
        certificate = new MockMultipartFile("certificate", "cert.p12", "application/octet-stream", new byte[] {2});
    }

    @Test
    void singleSigningFormatsRecordOneSuccessPerDocument() {
        when(signingService.signDocument(any(byte[].class), any(byte[].class), anyString()))
                .thenReturn(new byte[] {3});
        when(signingService.signDocumentPades(any(byte[].class), any(byte[].class), anyString(),
                any(SignatureMetadata.class), isNull())).thenReturn(new byte[] {4});
        PdfVerificationResult verification = PdfVerificationResult.builder().valid(false).build();
        when(signingService.signPadesAndVerify(any(byte[].class), any(byte[].class), anyString()))
                .thenReturn(new SigningService.PadesSignAndVerifyResult(new byte[] {5}, verification));

        assertEquals(HttpStatus.OK, controller.signDocument(document, certificate, "pass", request).getStatusCode());
        assertEquals(HttpStatus.OK, controller.signDocumentJson(document, certificate, "pass", request).getStatusCode());
        assertEquals(HttpStatus.OK, controller.signPdfPades(document, certificate, "pass",
                null, null, null, false, 0, "bottom-right", null, null, 240, 102,
                false, null, request).getStatusCode());
        assertEquals(HttpStatus.OK, controller.signPdfPadesJson(document, certificate, "pass",
                null, null, null, false, 0, "bottom-right", null, null, 240, 102,
                false, null, request).getStatusCode());
        assertEquals(HttpStatus.OK, controller.signPdfPadesAndVerify(document, certificate,
                "pass", null, null, request).getStatusCode());

        verify(tracker, times(5)).track(UsageTracker.DOCUMENTO_BAIXADO, "203.0.113.7");
        verifyNoMoreInteractions(tracker);
    }

    @Test
    void batchSigningRecordsEverySuccessAndPartialFailure() {
        MockMultipartFile bad = new MockMultipartFile("documents", "bad.pdf", "application/pdf", new byte[] {9});
        MockMultipartFile good2 = new MockMultipartFile("documents", "second.pdf", "application/pdf", new byte[] {3});
        when(signingService.signDocument(any(byte[].class), any(byte[].class), anyString()))
                .thenAnswer(call -> {
                    byte[] bytes = call.getArgument(0);
                    if (bytes[0] == 9) throw new InvalidDocumentException("invalid");
                    return new byte[] {4};
                });
        assertEquals(HttpStatus.OK, controller.signBatch(
                new MockMultipartFile[] {document, bad, good2}, certificate, "pass", request).getStatusCode());

        verify(tracker, times(2)).track(UsageTracker.DOCUMENTO_BAIXADO, "203.0.113.7");
        verify(tracker).track(UsageTracker.ASSINATURA_FALHOU, "203.0.113.7");
        verifyNoMoreInteractions(tracker);
    }

    @Test
    void pdfBatchRecordsEverySuccessAndPartialFailure() {
        MockMultipartFile bad = new MockMultipartFile("documents", "bad.pdf", "application/pdf", new byte[] {9});
        MockMultipartFile good2 = new MockMultipartFile("documents", "second.pdf", "application/pdf", new byte[] {3});
        when(signingService.signDocumentPades(any(byte[].class), any(byte[].class), anyString(),
                any(SignatureMetadata.class))).thenAnswer(call -> {
                    byte[] bytes = call.getArgument(0);
                    if (bytes[0] == 9) throw new InvalidDocumentException("invalid");
                    return new byte[] {4};
                });
        assertEquals(HttpStatus.OK, controller.signPdfPadesBatch(
                new MockMultipartFile[] {document, bad, good2}, certificate, "pass", null, null,
                null, false, 0, "bottom-right", 240, 102, request).getStatusCode());

        verify(tracker, times(2)).track(UsageTracker.DOCUMENTO_BAIXADO, "203.0.113.7");
        verify(tracker).track(UsageTracker.ASSINATURA_FALHOU, "203.0.113.7");
        verifyNoMoreInteractions(tracker);
    }

    @Test
    void unreadableBatchCertificateRecordsFailureForEverySubmittedDocument() throws IOException {
        org.springframework.web.multipart.MultipartFile unreadable = mock(org.springframework.web.multipart.MultipartFile.class);
        when(unreadable.getBytes()).thenThrow(new IOException("read failed"));
        assertEquals(HttpStatus.BAD_REQUEST, controller.signBatch(
                new MockMultipartFile[] {document, document}, unreadable, "pass", request).getStatusCode());

        verify(tracker, times(2)).track(UsageTracker.ASSINATURA_FALHOU, "203.0.113.7");
        verifyNoMoreInteractions(tracker);
    }

    @Test
    void invalidSignatureIsCompletedVerificationAndExceptionsAreFailures() {
        when(signingService.verifySignature(any(byte[].class), any(byte[].class))).thenReturn(false);
        when(signingService.verifyPdfSignature(any(byte[].class)))
                .thenReturn(PdfVerificationResult.builder().valid(false).build());
        assertEquals(HttpStatus.OK, controller.verifySignature(document, document, request).getStatusCode());
        assertEquals(HttpStatus.OK, controller.verifyPdfSignature(document, request).getStatusCode());

        when(signingService.verifySignature(any(byte[].class), any(byte[].class)))
                .thenThrow(new InvalidDocumentException("invalid"));
        assertThrows(InvalidDocumentException.class,
                () -> controller.verifySignature(document, document, request));

        verify(tracker, times(2)).track(UsageTracker.DOCUMENTO_VERIFICADO, "203.0.113.7");
        verify(tracker).track(UsageTracker.VERIFICACAO_FALHOU, "203.0.113.7");
        verifyNoMoreInteractions(tracker);
    }

    @Test
    void uploadAndServiceFailuresAreRecordedWithoutChangingResponses() throws IOException {
        org.springframework.web.multipart.MultipartFile unreadable = mock(org.springframework.web.multipart.MultipartFile.class);
        when(unreadable.getBytes()).thenThrow(new IOException("read failed"));
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.signDocumentJson(unreadable, certificate, "pass", request).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.verifyPdfSignature(unreadable, request).getStatusCode());
        when(signingService.signDocument(any(byte[].class), any(byte[].class), anyString()))
                .thenThrow(new InvalidDocumentException("invalid"));
        assertThrows(InvalidDocumentException.class,
                () -> controller.signDocumentJson(document, certificate, "pass", request));

        verify(tracker, times(2)).track(UsageTracker.ASSINATURA_FALHOU, "203.0.113.7");
        verify(tracker).track(UsageTracker.VERIFICACAO_FALHOU, "203.0.113.7");
        verifyNoMoreInteractions(tracker);
    }

    @Test
    void telemetryFailureCannotBreakSigningResponse() {
        when(signingService.signDocument(any(byte[].class), any(byte[].class), anyString()))
                .thenReturn(new byte[] {3});
        doThrow(new IllegalStateException("db unavailable"))
                .when(tracker).track(UsageTracker.DOCUMENTO_BAIXADO, "203.0.113.7");

        assertEquals(HttpStatus.OK,
                controller.signDocumentJson(document, certificate, "pass", request).getStatusCode());
    }

    @Test
    void multipartRoutesKeepSuccessAndFailureResponsesWhileRecordingOutcomes() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(signingService.signDocument(any(byte[].class), any(byte[].class), anyString()))
                .thenReturn(new byte[] {3});

        mvc.perform(multipart("/api/v1/sign/json")
                        .file(document).file(certificate).param("password", "pass"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        when(signingService.verifyPdfSignature(any(byte[].class)))
                .thenThrow(new InvalidDocumentException("invalid PDF"));
        mvc.perform(multipart("/api/v1/verify/pdf").file(document))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));

        verify(tracker).track(UsageTracker.DOCUMENTO_BAIXADO, "127.0.0.1");
        verify(tracker).track(UsageTracker.VERIFICACAO_FALHOU, "127.0.0.1");
        verifyNoMoreInteractions(tracker);
    }
}
