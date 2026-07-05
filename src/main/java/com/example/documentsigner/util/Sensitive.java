package com.example.documentsigner.util;

import java.util.Arrays;

/**
 * Helpers para minimizar o tempo de vida de segredos no heap (SEC #7/#8).
 *
 * <p><b>Limitação honesta:</b> a senha chega da camada HTTP como {@link String}
 * imutável (Spring {@code @RequestParam}), que não pode ser zerada — ela persiste
 * no heap até o GC. E a chave privada decifrada ({@code PrivateKey}) em geral não
 * é destruível no JCA (RSA lança em {@code destroy()}). Portanto isto é
 * <i>defesa em profundidade parcial</i>: zera as CÓPIAS de trabalho que
 * controlamos (o {@code char[]} da senha e o {@code byte[]} do PKCS12 com a chave
 * criptografada), reduzindo a janela de exposição em heap dump / swap.</p>
 */
public final class Sensitive {

    private Sensitive() {
    }

    /** Sobrescreve o array com zeros. No-op se {@code null}. */
    public static void wipe(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    /** Sobrescreve o array com '\0'. No-op se {@code null}. */
    public static void wipe(char[] data) {
        if (data != null) {
            Arrays.fill(data, '\0');
        }
    }
}
