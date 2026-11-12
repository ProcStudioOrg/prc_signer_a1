import assert from 'node:assert/strict';
import test from 'node:test';

import {
  boxToCss,
  clampBox,
  dragBox,
  fitBox,
  matchPreset,
  maxScaleFor,
  pageFitsStamp,
  presetBox,
  resizeBox,
  resolvePage,
  toPlacement,
  visiblePageSize,
} from './src/lib/placement.js';

const A4 = { width: 595.28, height: 841.89 };

test('página visível troca largura e altura quando o PDF está girado 90 ou 270', () => {
  const view = [0, 0, 595.28, 841.89];
  assert.deepEqual(visiblePageSize(view, 0), A4);
  assert.deepEqual(visiblePageSize(view, 180), A4);
  assert.deepEqual(visiblePageSize(view, 90), { width: 841.89, height: 595.28 });
  assert.deepEqual(visiblePageSize(view, 270), { width: 841.89, height: 595.28 });
});

test('página visível desconta a origem do CropBox', () => {
  assert.deepEqual(visiblePageSize([50, 60, 550, 800], 0), { width: 500, height: 740 });
});

test('atalhos de canto usam a mesma conta do backend (margem de 20 pt, origem embaixo)', () => {
  assert.deepEqual(presetBox('bottom-left', A4), { x: 20, y: 20, width: 240, height: 102 });
  assert.deepEqual(presetBox('bottom-right', A4), { x: 595.28 - 260, y: 20, width: 240, height: 102 });
  assert.deepEqual(presetBox('top-left', A4), { x: 20, y: 841.89 - 122, width: 240, height: 102 });
  assert.deepEqual(presetBox('top-right', A4), { x: 595.28 - 260, y: 841.89 - 122, width: 240, height: 102 });
});

test('a caixa em CSS mede a partir do topo: y=0 encosta embaixo', () => {
  const css = boxToCss({ x: 0, y: 0, width: 240, height: 102 }, A4);
  assert.equal(css.left, 0);
  assert.ok(Math.abs(css.top + css.height - 100) < 1e-9);

  const noTopo = boxToCss({ x: 0, y: A4.height - 102, width: 240, height: 102 }, A4);
  assert.ok(Math.abs(noTopo.top) < 1e-9);
});

test('arrastar para baixo na tela diminui o y do PDF', () => {
  const start = { x: 100, y: 400, width: 240, height: 102 };
  // tela a 0,5 px por ponto: 50 px = 100 pt
  const moved = dragBox(start, A4, 50, 50, 0.5);
  assert.equal(moved.x, 200);
  assert.equal(moved.y, 300);
});

test('arrastar não deixa a caixa sair da página', () => {
  const start = { x: 100, y: 400, width: 240, height: 102 };
  assert.deepEqual(dragBox(start, A4, 9999, 9999, 1), { x: A4.width - 240, y: 0, width: 240, height: 102 });
  assert.deepEqual(dragBox(start, A4, -9999, -9999, 1), { x: 0, y: A4.height - 102, width: 240, height: 102 });
});

test('redimensionar mantém a proporção e o canto superior esquerdo', () => {
  const start = { x: 100, y: 400, width: 240, height: 102 };
  const bigger = resizeBox(start, A4, 120, 1);
  assert.equal(bigger.width, 360);
  assert.equal(bigger.height, 153);
  assert.equal(bigger.x, 100);
  assert.equal(bigger.y + bigger.height, 502);
});

test('redimensionar respeita o mínimo, o máximo e a borda da página', () => {
  const start = { x: 100, y: 400, width: 240, height: 102 };
  assert.equal(resizeBox(start, A4, -9999, 1).width, 120);
  assert.equal(resizeBox(start, A4, 9999, 1).width, 480);

  const pertoDaBorda = { x: 400, y: 400, width: 120, height: 51 };
  const limitado = resizeBox(pertoDaBorda, A4, 9999, 1);
  assert.ok(Math.abs(limitado.x + limitado.width - A4.width) < 1e-6);

  const pertoDoChao = { x: 20, y: 0, width: 120, height: 51 };
  const noChao = resizeBox(pertoDoChao, A4, 9999, 1);
  assert.equal(noChao.height, 51);
  assert.equal(noChao.y, 0);
});

test('fitBox encolhe e reposiciona a caixa numa página menor', () => {
  const pequena = { width: 300, height: 200 };
  const fitted = fitBox({ x: 400, y: 600, width: 480, height: 204 }, pequena);
  assert.equal(fitted.width, 300);
  assert.equal(fitted.x, 0);
  assert.ok(fitted.y + fitted.height <= pequena.height + 1e-9);
});

test('página pequena demais para o carimbo mínimo é detectada', () => {
  assert.equal(pageFitsStamp({ width: 100, height: 100 }), false);
  assert.equal(pageFitsStamp(A4), true);
  assert.equal(maxScaleFor(A4), 2);
});

test('matchPreset reconhece o canto e ignora posição livre', () => {
  assert.equal(matchPreset(presetBox('top-right', A4), A4), 'top-right');
  assert.equal(matchPreset({ x: 123, y: 456, width: 240, height: 102 }, A4), null);
});

test('clampBox não muda o tamanho', () => {
  assert.deepEqual(clampBox({ x: -5, y: 900, width: 240, height: 102 }, A4), {
    x: 0,
    y: A4.height - 102,
    width: 240,
    height: 102,
  });
});

test('toPlacement arredonda em 2 casas e leva a página a partir de 1', () => {
  assert.deepEqual(toPlacement(3, { x: 100.126, y: 20.004, width: 240, height: 102 }), {
    page: 3,
    x: 100.13,
    y: 20,
    width: 240,
    height: 102,
  });
});

test('resolvePage trata 0 como última página e limita ao total', () => {
  assert.equal(resolvePage(0, 5), 5);
  assert.equal(resolvePage(1, 5), 1);
  assert.equal(resolvePage(9, 5), 5);
});
