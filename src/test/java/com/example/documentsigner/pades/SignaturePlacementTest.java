package com.example.documentsigner.pades;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import com.example.documentsigner.exception.InvalidSignaturePlacementException;
import com.example.documentsigner.pades.dto.PdfVerificationResult;
import com.example.documentsigner.pades.dto.SignatureMetadata;
import com.example.documentsigner.pades.dto.SignaturePosition;
import com.example.documentsigner.pades.dto.VisualSignatureConfig;
import com.example.documentsigner.support.SigningFixtures;
import com.example.documentsigner.support.SigningFixtures.Widget;

/**
 * Contrato de coordenadas da assinatura visível (v1.4.0): assina de verdade,
 * com um PKCS#12 descartável, e confere duas coisas independentes — o /Rect do
 * widget gravado no PDF e onde a tinta realmente aparece quando a página é
 * renderizada. A segunda é a que pega erro de rotação e de CropBox.
 */
class SignaturePlacementTest {

    private static final float A4_W = PDRectangle.A4.getWidth();   // 595.276
    private static final float A4_H = PDRectangle.A4.getHeight();  // 841.89

    private final PadesSignerService service = new PadesSignerService();

    private byte[] sign(byte[] pdf, VisualSignatureConfig config) throws Exception {
        return service.signPdfVisible(pdf, SigningFixtures.pkcs12(), SigningFixtures.PASSWORD,
            SignatureMetadata.builder().build(), config);
    }

    private static VisualSignatureConfig.Builder at(int page, float x, float y) {
        return VisualSignatureConfig.builder().enabled(true).page(page).x(x).y(y);
    }

    private static VisualSignatureConfig.Builder preset(int page, SignaturePosition position) {
        return VisualSignatureConfig.builder().enabled(true).page(page).position(position);
    }

    private static void assertRect(PDRectangle rect, float llx, float lly, float w, float h) {
        assertEquals(llx, rect.getLowerLeftX(), 0.01, "llx");
        assertEquals(lly, rect.getLowerLeftY(), 0.01, "lly");
        assertEquals(w, rect.getWidth(), 0.01, "width");
        assertEquals(h, rect.getHeight(), 0.01, "height");
    }

    /**
     * A tinta do carimbo tem de ocupar exatamente a caixa pedida, medida na
     * página VISÍVEL com origem embaixo à esquerda. Imagem a 72 dpi: 1 px = 1 pt.
     */
    private static void assertInkAt(BufferedImage image, float x, float y, float w, float h) {
        int[] ink = SigningFixtures.inkBounds(image);
        float top = image.getHeight() - y - h;
        assertEquals(x, ink[0], 2, "borda esquerda da tinta");
        assertEquals(top, ink[1], 2, "borda de cima da tinta");
        assertEquals(x + w, ink[2] + 1, 2, "borda direita da tinta");
        assertEquals(top + h, ink[3] + 1, 2, "borda de baixo da tinta");
    }

    // ---------- coordenadas livres ----------

    @Test
    void xyEhOCantoInferiorEsquerdoDaCaixaNaPaginaPedida() throws Exception {
        byte[] signed = sign(SigningFixtures.pdf(3), at(2, 100, 200).build());

        Widget widget = SigningFixtures.signatureWidget(signed);
        assertEquals(1, widget.pageIndex, "page=2 é a segunda página (conta a partir de 1)");
        assertRect(widget.rect, 100, 200, 240, 102);
        assertInkAt(SigningFixtures.render(signed, 1), 100, 200, 240, 102);
    }

    @Test
    void coordenadasValemMesmoSemPositionCustom() throws Exception {
        byte[] signed = sign(SigningFixtures.pdf(1),
            at(1, 30.5f, 40.25f).position(SignaturePosition.TOP_LEFT).build());

        assertRect(SigningFixtures.signatureWidget(signed).rect, 30.5f, 40.25f, 240, 102);
    }

    @Test
    void pageZeroEhAUltimaPagina() throws Exception {
        byte[] signed = sign(SigningFixtures.pdf(4), at(0, 50, 50).build());

        assertEquals(3, SigningFixtures.signatureWidget(signed).pageIndex);
    }

    @Test
    void caixaMaiorEscalaOCarimboSemSairDaCaixa() throws Exception {
        byte[] signed = sign(SigningFixtures.pdf(1), at(1, 60, 300).width(480).height(204).build());

        assertRect(SigningFixtures.signatureWidget(signed).rect, 60, 300, 480, 204);
        assertInkAt(SigningFixtures.render(signed, 0), 60, 300, 480, 204);
    }

    // ---------- atalhos de canto ----------

    @Test
    void atalhosDeCantoFicamA20PontosDaBorda() throws Exception {
        byte[] pdf = SigningFixtures.pdf(1);

        assertRect(SigningFixtures.signatureWidget(sign(pdf, preset(1, SignaturePosition.BOTTOM_LEFT).build())).rect,
            20, 20, 240, 102);
        assertRect(SigningFixtures.signatureWidget(sign(pdf, preset(1, SignaturePosition.BOTTOM_RIGHT).build())).rect,
            A4_W - 260, 20, 240, 102);
        assertRect(SigningFixtures.signatureWidget(sign(pdf, preset(1, SignaturePosition.TOP_LEFT).build())).rect,
            20, A4_H - 122, 240, 102);
        assertRect(SigningFixtures.signatureWidget(sign(pdf, preset(1, SignaturePosition.TOP_RIGHT).build())).rect,
            A4_W - 260, A4_H - 122, 240, 102);
    }

    // ---------- rotação e CropBox ----------

    @Test
    void paginaGirada90UsaAPaginaComoOLeitorVe() throws Exception {
        // A4 retrato com /Rotate 90: o leitor vê 842 × 595 (paisagem).
        byte[] signed = sign(SigningFixtures.pdf(1, PDRectangle.A4, null, 90, false), at(1, 100, 50).build());

        BufferedImage image = SigningFixtures.render(signed, 0);
        assertEquals(842, image.getWidth(), 1);
        assertEquals(595, image.getHeight(), 1);
        assertInkAt(image, 100, 50, 240, 102);
        // no espaço de usuário a caixa fica deitada: 102 × 240
        assertRect(SigningFixtures.signatureWidget(signed).rect, A4_W - 50 - 102, 100, 102, 240);
    }

    @Test
    void paginaGirada180E270TambemCaemOndeOLeitorVe() throws Exception {
        byte[] r180 = sign(SigningFixtures.pdf(1, PDRectangle.A4, null, 180, false), at(1, 100, 50).build());
        assertInkAt(SigningFixtures.render(r180, 0), 100, 50, 240, 102);
        assertRect(SigningFixtures.signatureWidget(r180).rect, A4_W - 100 - 240, A4_H - 50 - 102, 240, 102);

        byte[] r270 = sign(SigningFixtures.pdf(1, PDRectangle.A4, null, 270, false), at(1, 100, 50).build());
        assertInkAt(SigningFixtures.render(r270, 0), 100, 50, 240, 102);
        assertRect(SigningFixtures.signatureWidget(r270).rect, 50, A4_H - 100 - 240, 102, 240);
    }

    @Test
    void atalhoDeCantoEmPaginaGiradaContinuaNoCantoQueOLeitorVe() throws Exception {
        byte[] signed = sign(SigningFixtures.pdf(1, PDRectangle.A4, null, 90, false),
            preset(1, SignaturePosition.BOTTOM_RIGHT).build());

        assertInkAt(SigningFixtures.render(signed, 0), A4_H - 260, 20, 240, 102);
    }

    @Test
    void cropBoxDeslocadoContaAPartirDaPaginaVisivel() throws Exception {
        PDRectangle media = new PDRectangle(0, 0, 700, 900);
        PDRectangle crop = new PDRectangle(50, 60, 500, 740);
        byte[] signed = sign(SigningFixtures.pdf(1, media, crop, 0, false), at(1, 10, 15).build());

        assertRect(SigningFixtures.signatureWidget(signed).rect, 60, 75, 240, 102);
        BufferedImage image = SigningFixtures.render(signed, 0);
        assertEquals(500, image.getWidth(), 1);
        assertInkAt(image, 10, 15, 240, 102);
    }

    // ---------- a assinatura continua válida ----------

    @Test
    void assinaturaPosicionadaContinuaIntegra() throws Exception {
        byte[] signed = sign(SigningFixtures.pdf(2, PDRectangle.A4, null, 90, false), at(2, 300, 200).build());

        PdfVerificationResult result = service.verifyPdfSignature(signed);
        assertEquals(1, result.getTotalSignatures());
        assertTrue(result.getSignatures().get(0).isIntegrityValid());
        assertTrue(result.getSignatures().get(0).isCoversWholeDocument());
    }

    // ---------- recusas ----------

    private InvalidSignaturePlacementException refused(byte[] pdf, VisualSignatureConfig config) {
        return assertThrows(InvalidSignaturePlacementException.class, () -> sign(pdf, config));
    }

    @Test
    void paginaQueNaoExisteEhRecusada() throws Exception {
        InvalidSignaturePlacementException e = refused(SigningFixtures.pdf(2), at(5, 10, 10).build());

        assertEquals("INVALID_SIGNATURE_PAGE", e.getErrorCode());
        assertEquals("Invalid page number: 5. Document has 2 pages.", e.getMessage());
    }

    @Test
    void caixaForaDaPaginaEhRecusada() throws Exception {
        byte[] pdf = SigningFixtures.pdf(1);

        InvalidSignaturePlacementException direita = refused(pdf, at(1, 400, 100).build());
        assertEquals("INVALID_SIGNATURE_PLACEMENT", direita.getErrorCode());
        assertTrue(direita.getMessage().contains("does not fit page 1"), direita.getMessage());
        assertTrue(direita.getMessage().contains("595.28 x 841.89"), direita.getMessage());

        assertEquals("INVALID_SIGNATURE_PLACEMENT", refused(pdf, at(1, -5, 100).build()).getErrorCode());
        assertEquals("INVALID_SIGNATURE_PLACEMENT", refused(pdf, at(1, 100, 800).build()).getErrorCode());
        assertEquals("INVALID_SIGNATURE_PLACEMENT", refused(pdf, at(1, 100, -1).build()).getErrorCode());
    }

    @Test
    void caixaEncostadaNaBordaPassaComAFolgaDeArredondamento() throws Exception {
        byte[] signed = sign(SigningFixtures.pdf(1), at(1, A4_W - 240 + 0.4f, 0).build());

        assertEquals(0, SigningFixtures.signatureWidget(signed).pageIndex);
    }

    @Test
    void paginaGiradaValidaContraAPaginaVisivel() throws Exception {
        byte[] deitada = SigningFixtures.pdf(1, PDRectangle.A4, null, 90, false);

        // x=560 cabe nos 842 pt de largura visível; não caberia nos 595 do MediaBox.
        assertEquals(0, SigningFixtures.signatureWidget(sign(deitada, at(1, 560, 50).build())).pageIndex);
        // y=500 não cabe nos 595 pt de altura visível.
        assertEquals("INVALID_SIGNATURE_PLACEMENT", refused(deitada, at(1, 100, 500).build()).getErrorCode());
    }

    @Test
    void xSemYEhRecusado() throws Exception {
        VisualSignatureConfig soX = VisualSignatureConfig.builder().enabled(true).page(1).x(10f).build();

        assertEquals("Parameters x and y must be sent together.",
            refused(SigningFixtures.pdf(1), soX).getMessage());
    }

    @Test
    void positionCustomSemCoordenadasEhRecusado() throws Exception {
        assertEquals("position=custom requires x and y.",
            refused(SigningFixtures.pdf(1), preset(1, SignaturePosition.CUSTOM).build()).getMessage());
    }

    @Test
    void caixaMenorQueOMinimoEhRecusada() throws Exception {
        InvalidSignaturePlacementException e =
            refused(SigningFixtures.pdf(1), at(1, 10, 10).width(80).height(34).build());

        assertEquals("Signature box too small: 80 x 34 pt. Minimum is 120 x 51 pt.", e.getMessage());
    }

    @Test
    void atalhoDeCantoQueNaoCabeNaPaginaEhRecusado() throws Exception {
        byte[] pequena = SigningFixtures.pdf(1, new PDRectangle(200, 200), null, 0, false);

        assertEquals("INVALID_SIGNATURE_PLACEMENT",
            refused(pequena, preset(1, SignaturePosition.BOTTOM_RIGHT).build()).getErrorCode());
    }

    // ---------- conferência visual (PNG em target/placement-previews) ----------

    @Test
    void geraPngParaConferenciaVisual() throws Exception {
        byte[] retrato = sign(SigningFixtures.pdf(1, PDRectangle.A4, null, 0, true), at(1, 300, 120).build());
        SigningFixtures.savePng(retrato, 0, 1.5f, "a4-x300-y120.png");

        byte[] girada = sign(SigningFixtures.pdf(1, PDRectangle.A4, null, 90, true),
            at(1, 100, 50).width(360).height(153).build());
        SigningFixtures.savePng(girada, 0, 1.5f, "a4-rotate90-x100-y50-360x153.png");
    }
}
