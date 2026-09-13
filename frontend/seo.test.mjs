import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const indexHtml = await readFile(new URL('./index.html', import.meta.url), 'utf8');
const appSource = await readFile(new URL('./src/App.svelte', import.meta.url), 'utf8');

test('keeps structured data limited to content represented on the page', () => {
  assert.match(indexHtml, /"@type": "SoftwareApplication"/);
  assert.doesNotMatch(indexHtml, /"@type": "FAQPage"/);
});

test('does not claim that signing happens locally in the browser', () => {
  assert.doesNotMatch(appSource, /direto no navegador/i);
  assert.match(appSource, /Envie seus arquivos e assine em poucos passos/);
});

test('does not advertise a large social card without an image', () => {
  assert.match(indexHtml, /name="twitter:card" content="summary"/);
  assert.doesNotMatch(indexHtml, /summary_large_image/);
});

test('keeps the prerendered trust copy aligned with the rendered hero', () => {
  for (const copy of [
    'Padrão ICP-Brasil — PAdES e PKCS#7 (.p7s)',
    'Assinatura visível, na página e posição que você escolher',
    'Envie seus arquivos e assine em poucos passos'
  ]) {
    assert.match(indexHtml, new RegExp(copy.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
    assert.match(appSource, new RegExp(copy.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
});
