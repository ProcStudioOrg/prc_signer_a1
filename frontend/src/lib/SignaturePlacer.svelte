<script>
  // Prévia do PDF com a caixa da assinatura arrastável.
  //
  // O que sai daqui (`placement`) já está no contrato da API: página a partir
  // de 1 e caixa em pontos, origem no canto inferior esquerdo da página
  // visível. null = o usuário não mexeu; vale o atalho (página + canto)
  // escolhido no passo anterior. Toda a conta mora em ./placement.js.
  //
  // O pdf.js só é baixado quando este componente aparece.
  import { onDestroy, untrack } from 'svelte';
  import {
    BASE_HEIGHT,
    BASE_WIDTH,
    boxToCss,
    dragBox,
    fitBox,
    matchPreset,
    pageFitsStamp,
    presetBox,
    resizeBox,
    resolvePage,
    toPlacement,
    visiblePageSize,
  } from './placement.js';

  let {
    file = null,
    defaultPage = 0,
    defaultPosition = 'bottom-right',
    signerName = '',
    placement = $bindable(null),
  } = $props();

  const CORNERS = [
    { value: 'top-left', label: 'Superior esquerdo' },
    { value: 'top-right', label: 'Superior direito' },
    { value: 'bottom-left', label: 'Inferior esquerdo' },
    { value: 'bottom-right', label: 'Inferior direito' },
  ];

  let status = $state('loading'); // 'loading' | 'ready' | 'error'
  let numPages = $state(0);
  let viewPage = $state(1);
  let pageSize = $state(null); // página que está na tela, em pontos
  let rendering = $state(false);
  let stageWidth = $state(0);
  let dragging = $state(false);

  let canvas = $state(null);
  let pdfDoc = null;
  let loadingTask = null;
  let renderTask = null;
  let loadToken = 0;
  let gesture = null;

  const signPage = $derived(
    placement ? placement.page : numPages ? resolvePage(defaultPage, numPages) : 1
  );
  const onSignPage = $derived(status === 'ready' && viewPage === signPage);
  const fits = $derived(pageSize ? pageFitsStamp(pageSize) : true);
  const box = $derived.by(() => {
    if (!onSignPage || !pageSize || !fits) return null;
    if (placement) {
      return { x: placement.x, y: placement.y, width: placement.width, height: placement.height };
    }
    return presetBox(defaultPosition, pageSize);
  });
  const css = $derived(box && pageSize ? boxToCss(box, pageSize) : null);
  const pxPerPt = $derived(pageSize && stageWidth ? stageWidth / pageSize.width : 0);
  const activeCorner = $derived(box && pageSize ? matchPreset(box, pageSize) : null);

  // Carrega o PDF do arquivo escolhido.
  $effect(() => {
    const current = file;
    untrack(() => loadFile(current));
  });

  // Desenha a página visível (e redesenha se a largura da área mudar).
  $effect(() => {
    if (status !== 'ready' || !canvas || !stageWidth) return;
    const page = viewPage;
    const width = stageWidth;
    untrack(() => renderPage(page, width));
  });

  onDestroy(() => {
    loadToken++;
    cancelRender();
    loadingTask?.destroy();
  });

  let pdfjsPromise = null;
  function loadPdfjs() {
    pdfjsPromise ??= Promise.all([
      import('pdfjs-dist/legacy/build/pdf.mjs'),
      import('pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'),
    ]).then(([lib, worker]) => {
      lib.GlobalWorkerOptions.workerSrc = worker.default;
      return lib;
    });
    return pdfjsPromise;
  }

  async function loadFile(source) {
    const token = ++loadToken;
    cancelRender();
    loadingTask?.destroy();
    loadingTask = null;
    pdfDoc = null;
    numPages = 0;
    pageSize = null;
    if (!source) return;
    status = 'loading';
    try {
      const pdfjs = await loadPdfjs();
      const data = new Uint8Array(await source.arrayBuffer());
      if (token !== loadToken) return;
      loadingTask = pdfjs.getDocument({ data, isEvalSupported: false });
      const doc = await loadingTask.promise;
      if (token !== loadToken) return;
      pdfDoc = doc;
      numPages = doc.numPages;
      viewPage = Math.min(placement ? placement.page : resolvePage(defaultPage, doc.numPages), doc.numPages);
      status = 'ready';
    } catch (e) {
      if (token !== loadToken) return;
      status = 'error';
    }
  }

  function cancelRender() {
    renderTask?.cancel();
    renderTask = null;
  }

  async function renderPage(pageNumber, width) {
    if (!pdfDoc) return;
    const token = loadToken;
    cancelRender();
    rendering = true;
    try {
      const page = await pdfDoc.getPage(pageNumber);
      if (token !== loadToken || pageNumber !== viewPage) return;
      pageSize = visiblePageSize(page.view, page.rotate);
      const base = page.getViewport({ scale: 1 });
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const viewport = page.getViewport({ scale: (width / base.width) * dpr });
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      renderTask = page.render({ canvas, canvasContext: canvas.getContext('2d'), viewport });
      await renderTask.promise;
    } catch (e) {
      if (e?.name !== 'RenderingCancelledException' && token === loadToken) status = 'error';
    } finally {
      if (token === loadToken && pageNumber === viewPage) rendering = false;
    }
  }

  function setPlacement(value) {
    placement = value;
  }

  function goTo(page) {
    if (page < 1 || page > numPages || page === viewPage) return;
    viewPage = page;
  }

  function placeHere() {
    if (!pageSize) return;
    const size = placement ?? { width: BASE_WIDTH, height: BASE_HEIGHT };
    const seed = presetBox(defaultPosition, pageSize, { width: size.width, height: size.height });
    setPlacement(toPlacement(viewPage, fitBox(seed, pageSize)));
  }

  function moveToCorner(corner) {
    if (!pageSize || !fits) return;
    const size = box ?? { width: BASE_WIDTH, height: BASE_HEIGHT };
    const fitted = fitBox({ x: 0, y: 0, width: size.width, height: size.height }, pageSize);
    setPlacement(toPlacement(viewPage, presetBox(corner, pageSize, fitted)));
  }

  function resetToDefault() {
    setPlacement(null);
    viewPage = resolvePage(defaultPage, numPages);
  }

  function startGesture(event, mode) {
    if (!box || !pxPerPt) return;
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    gesture = { mode, pointerId: event.pointerId, x: event.clientX, y: event.clientY, box };
    dragging = true;
  }

  function moveGesture(event) {
    if (!gesture || event.pointerId !== gesture.pointerId || !pageSize) return;
    const dx = event.clientX - gesture.x;
    const dy = event.clientY - gesture.y;
    const next =
      gesture.mode === 'resize'
        ? resizeBox(gesture.box, pageSize, Math.max(dx, dy * (BASE_WIDTH / BASE_HEIGHT)), pxPerPt)
        : dragBox(gesture.box, pageSize, dx, dy, pxPerPt);
    setPlacement(toPlacement(viewPage, next));
  }

  function endGesture(event) {
    if (!gesture || event.pointerId !== gesture.pointerId) return;
    gesture = null;
    dragging = false;
  }

  const cornerLabel = (value) => CORNERS.find((c) => c.value === value)?.label.toLowerCase() ?? '';
</script>

<section class="placer" aria-label="Posição da assinatura no documento">
  <div class="placer-head">
    <h4>Onde a assinatura vai aparecer</h4>
  </div>

  {#if status === 'error'}
    <p class="placer-note placer-note-warn" role="status">
      Não foi possível mostrar a prévia deste PDF. Ele ainda pode ser assinado: a assinatura vai
      para a página e o canto escolhidos no passo 2.
    </p>
  {:else}
    <div class="placer-bar">
      <div class="placer-pages">
        <button
          type="button"
          class="placer-nav"
          onclick={() => goTo(viewPage - 1)}
          disabled={status !== 'ready' || viewPage <= 1}
          aria-label="Página anterior"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
        </button>
        <span class="placer-count" aria-live="polite">
          {#if status === 'ready'}Página {viewPage} de {numPages}{:else}Carregando…{/if}
        </span>
        <button
          type="button"
          class="placer-nav"
          onclick={() => goTo(viewPage + 1)}
          disabled={status !== 'ready' || viewPage >= numPages}
          aria-label="Próxima página"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
        </button>
      </div>

      <div class="placer-corners" role="group" aria-label="Atalhos de canto">
        {#each CORNERS as corner (corner.value)}
          <button
            type="button"
            class="placer-chip"
            class:active={onSignPage && activeCorner === corner.value}
            onclick={() => moveToCorner(corner.value)}
            disabled={status !== 'ready' || !fits}
          >
            {corner.label}
          </button>
        {/each}
      </div>
    </div>

    <div class="placer-stage-wrap">
      <div
        class="placer-stage"
        class:is-loading={status !== 'ready' || rendering}
        bind:clientWidth={stageWidth}
        style:aspect-ratio={pageSize ? `${pageSize.width} / ${pageSize.height}` : '210 / 297'}
      >
        <canvas bind:this={canvas} aria-hidden="true"></canvas>

        {#if status !== 'ready'}
          <div class="placer-overlay"><span class="placer-spinner" aria-hidden="true"></span> Carregando a prévia…</div>
        {:else if !fits}
          <div class="placer-overlay">Esta página é pequena demais para a assinatura visível.</div>
        {:else if !onSignPage}
          <div class="placer-overlay placer-overlay-action">
            <button type="button" class="placer-here" onclick={placeHere}>
              Colocar a assinatura nesta página
            </button>
          </div>
        {/if}

        {#if box && css}
          <div
            class="placer-box"
            class:dragging
            style:left="{css.left}%"
            style:top="{css.top}%"
            style:width="{css.width}%"
            style:height="{css.height}%"
            onpointerdown={(e) => startGesture(e, 'move')}
            onpointermove={moveGesture}
            onpointerup={endGesture}
            onpointercancel={endGesture}
            role="img"
            aria-label="Caixa da assinatura. Arraste para posicionar."
          >
            <div class="placer-stamp" style:transform="scale({(box.width * pxPerPt) / BASE_WIDTH})">
              <span class="stamp-title">ASSINADO DIGITALMENTE</span>
              <span class="stamp-rule"></span>
              <span class="stamp-row"><em>NOME</em>{signerName || 'Nome do titular'}</span>
              <span class="stamp-row"><em>AC</em>Autoridade certificadora</span>
              <span class="stamp-row"><em>DATA</em>Data e hora da assinatura</span>
              <span class="stamp-brand">ProcStudio</span>
            </div>
            <span
              class="placer-handle"
              onpointerdown={(e) => startGesture(e, 'resize')}
              onpointermove={moveGesture}
              onpointerup={endGesture}
              onpointercancel={endGesture}
              role="img"
              aria-label="Puxe para mudar o tamanho"
            ></span>
          </div>
        {/if}
      </div>
    </div>

    {#if status === 'ready'}
      <div class="placer-foot">
        {#if placement}
          <p class="placer-note">
            <strong>Posição ajustada</strong> — a assinatura fica na página {signPage} de {numPages}.
            {#if !onSignPage}
              <button type="button" class="placer-link" onclick={() => goTo(signPage)}>Ver a página {signPage}</button>
            {/if}
          </p>
          <button type="button" class="placer-link" onclick={resetToDefault}>Voltar ao padrão</button>
        {:else}
          <p class="placer-note">
            A assinatura fica no canto {cornerLabel(defaultPosition)} da página {signPage}. Arraste a
            caixa para mudar de lugar e puxe o canto azul para mudar o tamanho.
            {#if !onSignPage}
              <button type="button" class="placer-link" onclick={() => goTo(signPage)}>Ver a página {signPage}</button>
            {/if}
          </p>
        {/if}
      </div>
    {/if}
  {/if}
</section>

<style>
  .placer {
    margin: 0.9rem 0 1.1rem;
    padding: 0.9rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--surface-soft);
  }
  .placer-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem 1rem;
    margin-bottom: 0.7rem;
  }
  .placer-head h4 {
    margin: 0;
    font-size: 0.92rem;
    font-weight: 600;
    color: var(--text);
  }

  .placer-bar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem 1rem;
    margin-bottom: 0.7rem;
  }
  .placer-pages {
    display: flex;
    align-items: center;
    gap: 0.35rem;
  }
  .placer-nav {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border: 1px solid var(--border-strong);
    border-radius: 8px;
    background: var(--surface);
    color: var(--text);
    cursor: pointer;
  }
  .placer-nav:disabled {
    opacity: 0.4;
    cursor: default;
  }
  .placer-count {
    min-width: 8.2rem;
    text-align: center;
    font-size: 0.85rem;
    font-variant-numeric: tabular-nums;
    color: var(--text-muted);
  }
  .placer-corners {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.35rem;
  }
  .placer-chip {
    min-height: 36px;
    padding: 0.3rem 0.65rem;
    border: 1px solid var(--border-strong);
    border-radius: 8px;
    background: var(--surface);
    color: var(--text-muted);
    font: inherit;
    font-size: 0.78rem;
    cursor: pointer;
  }
  .placer-chip.active {
    border-color: var(--brand-border);
    background: var(--brand-soft);
    color: var(--brand-ink);
    font-weight: 600;
  }
  .placer-chip:disabled {
    opacity: 0.5;
    cursor: default;
  }

  .placer-stage-wrap {
    display: flex;
    justify-content: center;
    padding: 0.6rem;
    border-radius: var(--radius-sm);
    background: var(--surface-sunken);
  }
  .placer-stage {
    position: relative;
    width: 100%;
    max-width: 460px;
    background: var(--paper);
    box-shadow: var(--shadow-md);
    overflow: hidden;
  }
  .placer-stage canvas {
    display: block;
    width: 100%;
    height: 100%;
  }
  .placer-stage.is-loading canvas {
    opacity: 0.5;
  }
  .placer-overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    padding: 1rem;
    text-align: center;
    font-size: 0.85rem;
    color: var(--paper-muted);
  }
  .placer-overlay-action {
    align-items: flex-end;
    padding-bottom: 1.2rem;
  }
  .placer-here {
    min-height: 44px;
    padding: 0.55rem 1rem;
    border: 0;
    border-radius: 999px;
    background: var(--brand);
    color: var(--on-brand);
    font: inherit;
    font-size: 0.85rem;
    font-weight: 600;
    box-shadow: var(--shadow-md);
    cursor: pointer;
  }
  .placer-here:hover {
    background: var(--brand-hover);
  }
  .placer-spinner {
    width: 14px;
    height: 14px;
    border: 2px solid var(--border-strong);
    border-top-color: var(--brand);
    border-radius: 50%;
    animation: placer-spin 0.8s linear infinite;
  }
  @keyframes placer-spin {
    to {
      transform: rotate(360deg);
    }
  }

  /* A caixa: só ela (e a alça) capturam o toque; o resto da página rola normal. */
  .placer-box {
    position: absolute;
    box-sizing: border-box;
    outline: 2px solid var(--brand);
    outline-offset: -1px;
    background: color-mix(in srgb, var(--paper) 90%, transparent);
    box-shadow: 0 2px 10px color-mix(in srgb, var(--brand) 30%, transparent);
    cursor: grab;
    touch-action: none;
    user-select: none;
    -webkit-user-select: none;
    overflow: hidden;
  }
  .placer-box.dragging {
    cursor: grabbing;
    background: color-mix(in srgb, var(--paper) 78%, transparent);
  }
  .placer-stamp {
    position: absolute;
    top: 0;
    left: 0;
    width: 240px;
    height: 102px;
    transform-origin: top left;
    padding: 9px 10px;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    font-family: Helvetica, Arial, sans-serif;
    color: var(--paper-ink);
    pointer-events: none;
  }
  .stamp-title {
    font-size: 8.5px;
    font-weight: 700;
    letter-spacing: 0.7px;
    color: var(--paper-accent);
    line-height: 1;
  }
  .stamp-rule {
    width: 26px;
    height: 1px;
    margin: 4px 0 5px;
    background: var(--paper-accent);
  }
  .stamp-row {
    display: flex;
    font-size: 7px;
    line-height: 11px;
    white-space: nowrap;
  }
  .stamp-row em {
    flex: 0 0 46px;
    font-style: normal;
    font-size: 6px;
    color: var(--paper-muted);
  }
  .stamp-brand {
    margin-top: auto;
    font-size: 12px;
    font-weight: 700;
    line-height: 1;
    color: var(--paper-accent);
  }
  .placer-handle {
    position: absolute;
    right: -6px;
    bottom: -6px;
    width: 36px;
    height: 36px;
    cursor: nwse-resize;
    touch-action: none;
  }
  .placer-handle::after {
    content: '';
    position: absolute;
    right: 6px;
    bottom: 6px;
    width: 14px;
    height: 14px;
    border-radius: 3px 0 0 0;
    background: var(--brand);
  }

  .placer-foot {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.3rem 1rem;
    margin-top: 0.7rem;
  }
  .placer-note {
    margin: 0;
    font-size: 0.82rem;
    line-height: 1.45;
    color: var(--text-muted);
  }
  .placer-note strong {
    color: var(--text);
  }
  .placer-note-warn {
    padding: 0.6rem 0.75rem;
    border: 1px solid var(--border-strong);
    border-radius: 8px;
    background: var(--surface);
  }
  .placer-link {
    padding: 0.35rem 0;
    border: 0;
    background: none;
    color: var(--brand-ink);
    font: inherit;
    font-size: 0.82rem;
    font-weight: 600;
    text-decoration: underline;
    text-underline-offset: 2px;
    cursor: pointer;
  }

  @media (max-width: 560px) {
    .placer {
      padding: 0.7rem;
    }
    .placer-bar {
      flex-direction: column;
      align-items: stretch;
    }
    .placer-pages {
      justify-content: space-between;
    }
    .placer-nav {
      width: 44px;
      height: 44px;
    }
    .placer-chip {
      min-height: 44px;
    }
    .placer-stage-wrap {
      padding: 0.35rem;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .placer-spinner {
      animation: none;
    }
  }
</style>
