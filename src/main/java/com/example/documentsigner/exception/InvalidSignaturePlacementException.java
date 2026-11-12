package com.example.documentsigner.exception;

/**
 * A posição pedida para a assinatura visível não cabe no documento: página
 * inexistente, caixa fora da página, tamanho abaixo do mínimo ou parâmetros
 * incoerentes. Culpa do pedido, não do serviço — vira 400.
 */
public class InvalidSignaturePlacementException extends SigningException {

    public static final String CODE_PAGE = "INVALID_SIGNATURE_PAGE";
    public static final String CODE_PLACEMENT = "INVALID_SIGNATURE_PLACEMENT";

    public InvalidSignaturePlacementException(String message, String errorCode) {
        super(message, errorCode);
    }

    public static InvalidSignaturePlacementException page(String message) {
        return new InvalidSignaturePlacementException(message, CODE_PAGE);
    }

    public static InvalidSignaturePlacementException placement(String message) {
        return new InvalidSignaturePlacementException(message, CODE_PLACEMENT);
    }
}
