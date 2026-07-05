package com.example.documentsigner.api;

import com.example.documentsigner.api.dto.ErrorResponse;
import com.example.documentsigner.exception.ExpiredCertificateException;
import com.example.documentsigner.exception.InvalidCertificateException;
import com.example.documentsigner.exception.InvalidDocumentException;
import com.example.documentsigner.exception.InvalidPasswordException;
import com.example.documentsigner.exception.SigningException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPassword(InvalidPasswordException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(e.getMessage(), e.getErrorCode()));
    }

    @ExceptionHandler(InvalidCertificateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCertificate(InvalidCertificateException e) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(e.getMessage(), e.getErrorCode()));
    }

    @ExceptionHandler(ExpiredCertificateException.class)
    public ResponseEntity<ErrorResponse> handleExpiredCertificate(ExpiredCertificateException e) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(e.getMessage(), e.getErrorCode()));
    }

    @ExceptionHandler(InvalidDocumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDocument(InvalidDocumentException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(e.getMessage(), e.getErrorCode()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("File size exceeds maximum limit", "FILE_TOO_LARGE"));
    }

    /**
     * Corpo não-multipart ou parte de formulário ausente (ex.: falta o arquivo
     * `certificate`/`document`). Culpa do cliente → 400, não 500.
     */
    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleMultipart(Exception e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("Malformed multipart request or missing file part", "BAD_REQUEST"));
    }

    /** Parâmetro obrigatório ausente (ex.: falta `password`) → 400. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("Missing required parameter: " + e.getParameterName(), "BAD_REQUEST"));
    }

    /** Método HTTP não suportado na rota (ex.: GET numa rota POST) → 405. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse("HTTP method not allowed on this endpoint", "METHOD_NOT_ALLOWED"));
    }

    /** Content-Type não suportado → 415. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("Unsupported content type", "UNSUPPORTED_MEDIA_TYPE"));
    }

    @ExceptionHandler(SigningException.class)
    public ResponseEntity<ErrorResponse> handleSigningException(SigningException e) {
        // Detalhe (mensagem da lib de cripto/keystore) só no log; cliente recebe genérico.
        String correlationId = UUID.randomUUID().toString();
        log.error("SigningException [{}]: {}", correlationId, e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(
                    "Signing/verification failed. Reference: " + correlationId, e.getErrorCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e) {
        // Nunca ecoar e.getMessage() ao cliente (information disclosure). Log com
        // correlationId; resposta genérica com a referência p/ suporte.
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled exception [{}]", correlationId, e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal server error. Reference: " + correlationId, "INTERNAL_ERROR"));
    }
}
