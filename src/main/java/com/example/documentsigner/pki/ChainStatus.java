package com.example.documentsigner.pki;

/**
 * Eixo de CONFIANÇA da cadeia, separado do eixo de VALIDADE (data).
 *
 * <p>Um certificado pode estar dentro da validade e mesmo assim ser
 * autoassinado — são perguntas diferentes e não podem colapsar num booleano.</p>
 */
public enum ChainStatus {
    /** A cadeia fecha contra o truststore ICP-Brasil configurado. */
    VERIFIED("verified"),
    /** Sabemos que NÃO presta: autoassinado, ou raiz recusada pelo truststore. */
    UNTRUSTED("untrusted"),
    /** Não foi possível conferir — sem truststore configurado, por exemplo. */
    UNVERIFIED("unverified");

    private final String wire;

    ChainStatus(String wire) {
        this.wire = wire;
    }

    /** Valor serializado na API. Estável — o Rails grava isto. */
    public String wire() {
        return wire;
    }
}
