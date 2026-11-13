package com.example.documentsigner.pades;

import com.example.documentsigner.exception.InvalidSignaturePlacementException;
import com.example.documentsigner.pades.dto.SignaturePosition;
import com.example.documentsigner.pades.dto.VisualSignatureConfig;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.util.Locale;

/**
 * Resolve ONDE a assinatura visível vai parar. É o único lugar que conhece o
 * contrato de coordenadas — o que o frontend desenha e o que o PDF recebe saem
 * daqui.
 *
 * <h3>Contrato (v1.4.0)</h3>
 * <ul>
 *   <li><b>Unidade:</b> pontos PDF (1/72 de polegada).</li>
 *   <li><b>Página:</b> {@code page} conta a partir de 1; {@code 0} (ou negativo)
 *       significa "última página".</li>
 *   <li><b>Espaço:</b> a página <i>como o leitor a vê</i> — recortada pelo
 *       CropBox (que por sua vez é limitado ao MediaBox) e já girada pelo
 *       {@code /Rotate}. Largura e altura da página são as desse retângulo
 *       visível.</li>
 *   <li><b>Origem:</b> canto INFERIOR ESQUERDO da página visível, x para a
 *       direita, y para cima (a convenção do PDF). {@code x}/{@code y} são o
 *       canto inferior esquerdo da caixa; {@code width}/{@code height} são o
 *       tamanho da caixa como ela aparece na tela.</li>
 *   <li><b>Validação:</b> a caixa inteira precisa caber na página visível
 *       (tolerância de {@value #TOLERANCE} pt) e ter no mínimo
 *       {@value #MIN_WIDTH} × {@value #MIN_HEIGHT} pt.</li>
 * </ul>
 *
 * <p>Para uma página sem rotação e com CropBox = MediaBox na origem — a
 * esmagadora maioria — isto é idêntico ao espaço de usuário cru do PDF, que é
 * o que o serviço já fazia antes da 1.4.0.</p>
 */
public final class SignaturePlacement {

    /** Tamanho de projeto do carimbo; o desenho escala a partir daqui. */
    public static final float BASE_WIDTH = 240f;
    public static final float BASE_HEIGHT = 102f;
    /** Abaixo disto o texto do carimbo (7 pt no tamanho base) fica ilegível. */
    public static final float MIN_WIDTH = 120f;
    public static final float MIN_HEIGHT = 51f;
    /** Margem dos atalhos de canto (top-left, bottom-right, ...). */
    public static final float PRESET_MARGIN = 20f;
    /** Folga para arredondamento de quem converte pixel em ponto. */
    public static final float TOLERANCE = 0.5f;

    private final int pageIndex;
    private final int rotation;
    private final PDRectangle cropBox;
    private final float pageWidth;
    private final float pageHeight;
    private final float x;
    private final float y;
    private final float width;
    private final float height;

    private SignaturePlacement(int pageIndex, int rotation, PDRectangle cropBox,
                               float pageWidth, float pageHeight,
                               float x, float y, float width, float height) {
        this.pageIndex = pageIndex;
        this.rotation = rotation;
        this.cropBox = cropBox;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public static SignaturePlacement resolve(PDDocument document, VisualSignatureConfig config) {
        int totalPages = document.getNumberOfPages();
        int pageIndex = config.getPage() <= 0 ? totalPages - 1 : config.getPage() - 1;
        if (pageIndex < 0 || pageIndex >= totalPages) {
            throw InvalidSignaturePlacementException.page(
                "Invalid page number: " + config.getPage() + ". Document has " + totalPages
                + (totalPages == 1 ? " page." : " pages."));
        }

        PDPage page = document.getPage(pageIndex);
        PDRectangle crop = page.getCropBox();
        int rotation = page.getRotation();
        boolean sideways = rotation == 90 || rotation == 270;
        float pageWidth = sideways ? crop.getHeight() : crop.getWidth();
        float pageHeight = sideways ? crop.getWidth() : crop.getHeight();

        float width = config.getWidth();
        float height = config.getHeight();
        if (!isFinite(width) || !isFinite(height) || width < MIN_WIDTH || height < MIN_HEIGHT) {
            throw InvalidSignaturePlacementException.placement(String.format(Locale.ROOT,
                "Signature box too small: %s x %s pt. Minimum is %s x %s pt.",
                fmt(width), fmt(height), fmt(MIN_WIDTH), fmt(MIN_HEIGHT)));
        }

        Float cx = config.getX();
        Float cy = config.getY();
        if ((cx == null) != (cy == null)) {
            throw InvalidSignaturePlacementException.placement(
                "Parameters x and y must be sent together.");
        }

        float x;
        float y;
        if (cx != null) {
            if (!isFinite(cx) || !isFinite(cy)) {
                throw InvalidSignaturePlacementException.placement(
                    "Parameters x and y must be finite numbers.");
            }
            x = cx;
            y = cy;
        } else {
            SignaturePosition position = config.getPosition() != null
                ? config.getPosition() : SignaturePosition.BOTTOM_RIGHT;
            switch (position) {
                case CUSTOM:
                    throw InvalidSignaturePlacementException.placement(
                        "position=custom requires x and y.");
                case TOP_LEFT:
                    x = PRESET_MARGIN;
                    y = pageHeight - height - PRESET_MARGIN;
                    break;
                case TOP_RIGHT:
                    x = pageWidth - width - PRESET_MARGIN;
                    y = pageHeight - height - PRESET_MARGIN;
                    break;
                case BOTTOM_LEFT:
                    x = PRESET_MARGIN;
                    y = PRESET_MARGIN;
                    break;
                case BOTTOM_RIGHT:
                default:
                    x = pageWidth - width - PRESET_MARGIN;
                    y = PRESET_MARGIN;
                    break;
            }
        }

        if (x < -TOLERANCE || y < -TOLERANCE
                || x + width > pageWidth + TOLERANCE || y + height > pageHeight + TOLERANCE) {
            throw InvalidSignaturePlacementException.placement(String.format(Locale.ROOT,
                "Signature box (x=%s, y=%s, width=%s, height=%s) does not fit page %d, "
                + "which is %s x %s pt. Origin is the bottom-left corner of the visible page.",
                fmt(x), fmt(y), fmt(width), fmt(height), pageIndex + 1,
                fmt(pageWidth), fmt(pageHeight)));
        }

        return new SignaturePlacement(pageIndex, rotation, crop, pageWidth, pageHeight,
            x, y, width, height);
    }

    /**
     * Converte um retângulo dado no espaço da página visível (origem embaixo à
     * esquerda) para o espaço de usuário do PDF, desfazendo CropBox e /Rotate.
     */
    public PDRectangle toUserSpace(float dx, float dy, float w, float h) {
        float cw = cropBox.getWidth();
        float ch = cropBox.getHeight();
        float ux;
        float uy;
        float uw = w;
        float uh = h;
        switch (rotation) {
            case 90:
                ux = cw - dy - h;
                uy = dx;
                uw = h;
                uh = w;
                break;
            case 180:
                ux = cw - dx - w;
                uy = ch - dy - h;
                break;
            case 270:
                ux = dy;
                uy = ch - dx - w;
                uw = h;
                uh = w;
                break;
            default:
                ux = dx;
                uy = dy;
                break;
        }
        return new PDRectangle(cropBox.getLowerLeftX() + ux, cropBox.getLowerLeftY() + uy, uw, uh);
    }

    /** /Rect do widget da assinatura, em espaço de usuário. */
    public PDRectangle getWidgetRectangle() {
        return toUserSpace(x, y, width, height);
    }

    /** Fator uniforme aplicado ao desenho de 240 × 102 para caber na caixa. */
    public float getScale() {
        return Math.min(width / BASE_WIDTH, height / BASE_HEIGHT);
    }

    public int getPageIndex() { return pageIndex; }
    public int getRotation() { return rotation; }
    public float getPageWidth() { return pageWidth; }
    public float getPageHeight() { return pageHeight; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getWidth() { return width; }
    public float getHeight() { return height; }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static String fmt(float v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e7) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
