// Geometria da assinatura visível — o mesmo contrato do backend
// (SignaturePlacement.java, v1.4.0). Tudo aqui é função pura.
//
//   • Unidade: pontos PDF (1/72").
//   • Página: como o leitor a vê (CropBox, já girada pelo /Rotate).
//   • Origem: canto INFERIOR ESQUERDO da página; y cresce para cima.
//   • Caixa: { x, y, width, height } — x/y são o canto inferior esquerdo.
//   • `page` conta a partir de 1; 0 = última página.
//
// A tela tem a origem no topo; a conversão vive só em boxToCss/dragBox.

export const BASE_WIDTH = 240;
export const BASE_HEIGHT = 102;
export const MIN_SCALE = 0.5; // 120 × 51 pt — o mínimo que o backend aceita
export const MAX_SCALE = 2;
export const PRESET_MARGIN = 20;
export const PRESETS = ['top-left', 'top-right', 'bottom-left', 'bottom-right'];

const round2 = (n) => Math.round(n * 100) / 100;
const clamp = (n, min, max) => Math.min(Math.max(n, min), max);

/** Tamanho da página visível a partir do que o pdf.js entrega (view + rotate). */
export function visiblePageSize(view, rotate) {
  const width = view[2] - view[0];
  const height = view[3] - view[1];
  return Math.abs(rotate) % 180 === 90 ? { width: height, height: width } : { width, height };
}

/** Maior escala do carimbo que ainda cabe inteira na página. */
export function maxScaleFor(page) {
  return Math.min(MAX_SCALE, page.width / BASE_WIDTH, page.height / BASE_HEIGHT);
}

/** A página comporta o carimbo no tamanho mínimo? */
export function pageFitsStamp(page) {
  return maxScaleFor(page) >= MIN_SCALE;
}

/** Caixa de um atalho de canto — mesma conta do backend (margem de 20 pt). */
export function presetBox(position, page, size = { width: BASE_WIDTH, height: BASE_HEIGHT }) {
  const { width, height } = size;
  const left = PRESET_MARGIN;
  const right = page.width - width - PRESET_MARGIN;
  const bottom = PRESET_MARGIN;
  const top = page.height - height - PRESET_MARGIN;
  switch (position) {
    case 'top-left':
      return { x: left, y: top, width, height };
    case 'top-right':
      return { x: right, y: top, width, height };
    case 'bottom-left':
      return { x: left, y: bottom, width, height };
    default:
      return { x: right, y: bottom, width, height };
  }
}

/** Mantém a caixa inteira dentro da página (não muda o tamanho). */
export function clampBox(box, page) {
  return {
    ...box,
    x: clamp(box.x, 0, Math.max(0, page.width - box.width)),
    y: clamp(box.y, 0, Math.max(0, page.height - box.height)),
  };
}

/**
 * Ajusta uma caixa a uma página: reduz (mantendo a proporção) se for maior que
 * a página e depois prende dentro dela. Usado ao trocar de página ou de arquivo.
 */
export function fitBox(box, page) {
  const scale = clamp(box.width / BASE_WIDTH, MIN_SCALE, Math.max(MIN_SCALE, maxScaleFor(page)));
  return clampBox({ ...box, width: BASE_WIDTH * scale, height: BASE_HEIGHT * scale }, page);
}

/**
 * Arrasta a caixa. dxPx/dyPx são o deslocamento do ponteiro na tela (y para
 * baixo); pxPerPt é quantos pixels de tela valem um ponto do PDF.
 */
export function dragBox(startBox, page, dxPx, dyPx, pxPerPt) {
  return clampBox(
    { ...startBox, x: startBox.x + dxPx / pxPerPt, y: startBox.y - dyPx / pxPerPt },
    page
  );
}

/**
 * Redimensiona pela alça do canto inferior direito, com a proporção travada.
 * O canto superior esquerdo fica onde está; a caixa nunca passa da página nem
 * dos limites de escala.
 */
export function resizeBox(startBox, page, dxPx, pxPerPt) {
  const top = startBox.y + startBox.height;
  const roomRight = page.width - startBox.x;
  const roomDown = top;
  const maxScale = Math.min(MAX_SCALE, roomRight / BASE_WIDTH, roomDown / BASE_HEIGHT);
  const wanted = (startBox.width + dxPx / pxPerPt) / BASE_WIDTH;
  const scale = clamp(wanted, MIN_SCALE, Math.max(MIN_SCALE, maxScale));
  const width = BASE_WIDTH * scale;
  const height = BASE_HEIGHT * scale;
  return clampBox({ x: startBox.x, y: top - height, width, height }, page);
}

/** Posição da caixa em % da página, para o CSS (origem no topo esquerdo). */
export function boxToCss(box, page) {
  return {
    left: (box.x / page.width) * 100,
    top: ((page.height - box.y - box.height) / page.height) * 100,
    width: (box.width / page.width) * 100,
    height: (box.height / page.height) * 100,
  };
}

/** Qual atalho de canto esta caixa representa (ou null se foi posta à mão). */
export function matchPreset(box, page) {
  for (const position of PRESETS) {
    const preset = presetBox(position, page, box);
    if (Math.abs(preset.x - box.x) < 0.5 && Math.abs(preset.y - box.y) < 0.5) return position;
  }
  return null;
}

/** O que vai para a API: página a partir de 1 e caixa em pontos, 2 casas. */
export function toPlacement(pageNumber, box) {
  return {
    page: pageNumber,
    x: round2(box.x),
    y: round2(box.y),
    width: round2(box.width),
    height: round2(box.height),
  };
}

/** Página pedida nos atalhos (0 = última) resolvida contra o total do arquivo. */
export function resolvePage(requested, totalPages) {
  if (!requested || requested <= 0) return totalPages;
  return clamp(requested, 1, totalPages);
}
