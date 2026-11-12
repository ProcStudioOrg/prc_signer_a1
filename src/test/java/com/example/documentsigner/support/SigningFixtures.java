package com.example.documentsigner.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Material descartável para os testes de assinatura: um PKCS#12 autoassinado
 * gerado na hora (nunca um certificado real) e PDFs sintéticos com a geometria
 * que cada teste precisa.
 */
public final class SigningFixtures {

    public static final String PASSWORD = "senha-de-teste";

    private static byte[] cachedPkcs12;

    private SigningFixtures() {
    }

    public static synchronized byte[] pkcs12() throws Exception {
        if (cachedPkcs12 != null) {
            return cachedPkcs12.clone();
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=Fulana de Teste, O=Certificado Descartavel de Teste");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), new Date(now - 86400000L),
            new Date(now + 30L * 86400000L), name, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("teste", keyPair.getPrivate(), PASSWORD.toCharArray(),
            new Certificate[] {certificate});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        keyStore.store(out, PASSWORD.toCharArray());
        cachedPkcs12 = out.toByteArray();
        return cachedPkcs12.clone();
    }

    /** PDF em branco com {@code pages} páginas A4 retrato. */
    public static byte[] pdf(int pages) throws Exception {
        return pdf(pages, PDRectangle.A4, null, 0, false);
    }

    /**
     * PDF sintético. Com {@code withText}, cada página ganha um parágrafo — só
     * para a conferência visual; os testes de pixel usam páginas em branco.
     */
    public static byte[] pdf(int pages, PDRectangle mediaBox, PDRectangle cropBox,
                             int rotation, boolean withText) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(mediaBox);
                if (cropBox != null) {
                    page.setCropBox(cropBox);
                }
                page.setRotation(rotation);
                document.addPage(page);
                if (withText) {
                    try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                        cs.beginText();
                        cs.setFont(PDType1Font.HELVETICA, 12);
                        cs.setLeading(18);
                        cs.newLineAtOffset(mediaBox.getLowerLeftX() + 72, mediaBox.getUpperRightY() - 90);
                        cs.showText("CONTRATO DE HONORARIOS - pagina " + (i + 1));
                        for (int line = 0; line < 14; line++) {
                            cs.newLine();
                            cs.showText("Texto de exemplo para conferir onde o carimbo cai na pagina.");
                        }
                        cs.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** Onde a assinatura foi parar: página (índice a partir de 0) e /Rect cru. */
    public static final class Widget {
        public final int pageIndex;
        public final PDRectangle rect;

        Widget(int pageIndex, PDRectangle rect) {
            this.pageIndex = pageIndex;
            this.rect = rect;
        }
    }

    public static Widget signatureWidget(byte[] signedPdf) throws Exception {
        try (PDDocument document = PDDocument.load(signedPdf)) {
            PDSignatureField field = document.getSignatureFields().get(0);
            PDAnnotationWidget widget = field.getWidgets().get(0);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                for (Object annotation : document.getPage(i).getAnnotations()) {
                    if (annotation instanceof PDAnnotationWidget
                            && ((PDAnnotationWidget) annotation).getCOSObject() == widget.getCOSObject()) {
                        return new Widget(i, widget.getRectangle());
                    }
                }
            }
            throw new AssertionError("widget da assinatura não está em nenhuma página");
        }
    }

    /** Renderiza a página a 72 dpi: 1 pixel = 1 ponto da página visível. */
    public static BufferedImage render(byte[] pdf, int pageIndex) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFRenderer(document).renderImage(pageIndex, 1f);
        }
    }

    public static void savePng(byte[] pdf, int pageIndex, float scale, String name) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            File dir = new File("target/placement-previews");
            dir.mkdirs();
            ImageIO.write(new PDFRenderer(document).renderImage(pageIndex, scale), "png",
                new File(dir, name));
        }
    }

    /**
     * Caixa que envolve tudo o que não é branco, em coordenadas de imagem
     * (origem no topo esquerdo): {left, top, right, bottom}, limites inclusivos.
     */
    public static int[] inkBounds(BufferedImage image) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = -1;
        int bottom = -1;
        for (int py = 0; py < image.getHeight(); py++) {
            for (int px = 0; px < image.getWidth(); px++) {
                int rgb = image.getRGB(px, py);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                if (r < 245 || g < 245 || b < 245) {
                    left = Math.min(left, px);
                    top = Math.min(top, py);
                    right = Math.max(right, px);
                    bottom = Math.max(bottom, py);
                }
            }
        }
        return new int[] {left, top, right, bottom};
    }
}
