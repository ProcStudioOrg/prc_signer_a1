<script>
  import { onMount } from 'svelte';
  import logo from './assets/logo.png';
  import logoWhite from './assets/logo-white.svg';

  const API_BASE = '/api/v1';
  const SYSTEM_URL = 'https://procstudio.com.br';

  let certificateFiles = [];
  let certificatePassword = '';
  let outputFormat = 'pades';
  let includeVisualSignature = true;
  let pageMode = 'last'; // 'last' | 'first' | 'custom'
  let customPage = 1;
  let signaturePosition = 'bottom-right';
  let documentsToSign = [];
  let statusMessage = '';
  let statusType = 'info'; // 'info', 'success', 'error'
  let isLoading = false;
  let certificateInfo = null;

  let view = 'form'; // 'form' | 'done'
  let doneSummary = '';

  let certificateInput;
  let documentInput;

  // ── Theme ──────────────────────────────────────────────────────
  let dark = false;
  onMount(() => {
    const stored = localStorage.getItem('ps-theme');
    dark = stored
      ? stored === 'dark'
      : window.matchMedia('(prefers-color-scheme: dark)').matches;
  });
  function toggleTheme() {
    dark = !dark;
    const v = dark ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', v);
    try { localStorage.setItem('ps-theme', v); } catch (e) {}
  }

  function handleCertificateUpload(event) {
    certificateFiles = Array.from(event.target.files);
    certificateInfo = null;
  }

  function handleDocumentUpload(event) {
    documentsToSign = Array.from(event.target.files);
  }

  function setStatus(message, type = 'info') {
    statusMessage = message;
    statusType = type;
  }

  function setDone(summary) {
    doneSummary = summary;
    view = 'done';
    statusMessage = '';
  }

  function signAnother() {
    documentsToSign = [];
    doneSummary = '';
    statusMessage = '';
    view = 'form';
  }

  function downloadBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  async function checkCertificate() {
    if (certificateFiles.length === 0) {
      setStatus('Por favor, selecione um arquivo de certificado primeiro.', 'error');
      return;
    }
    if (!certificatePassword) {
      setStatus('Por favor, insira a senha do certificado.', 'error');
      return;
    }

    isLoading = true;
    setStatus(`Verificando certificado: ${certificateFiles[0].name}...`);

    try {
      const formData = new FormData();
      formData.append('certificate', certificateFiles[0]);
      formData.append('password', certificatePassword);

      const res = await fetch(`${API_BASE}/certificate/info`, { method: 'POST', body: formData });
      const data = await res.json();

      if (!res.ok) {
        setStatus(data.message || 'Erro ao validar certificado.', 'error');
        certificateInfo = null;
      } else {
        certificateInfo = data;
        const expiry = data.expired
          ? ' (EXPIRADO!)'
          : ` (expira em ${data.daysUntilExpiry} dias)`;
        setStatus(`Certificado valido: ${data.commonName}${expiry}`, data.expired ? 'error' : 'success');
      }
    } catch (err) {
      setStatus('Erro de conexao com o servidor. Verifique se o backend esta rodando.', 'error');
      certificateInfo = null;
    } finally {
      isLoading = false;
    }
  }

  async function signDocuments() {
    if (certificateFiles.length === 0) {
      setStatus('Por favor, selecione um arquivo de certificado.', 'error');
      return;
    }
    if (!certificatePassword) {
      setStatus('Por favor, insira a senha do certificado.', 'error');
      return;
    }
    if (documentsToSign.length === 0) {
      setStatus('Por favor, selecione o(s) documento(s) para assinar.', 'error');
      return;
    }

    isLoading = true;
    const fileNames = documentsToSign.map(f => f.name).join(', ');
    setStatus(`Assinando ${documentsToSign.length} arquivo(s): ${fileNames}...`);

    try {
      if (isPades) {
        await signPades();
      } else {
        await signP7s();
      }
    } catch (err) {
      setStatus(`Erro ao assinar: ${err.message}`, 'error');
    } finally {
      isLoading = false;
    }
  }

  async function signPades() {
    if (documentsToSign.length === 1) {
      const formData = new FormData();
      formData.append('document', documentsToSign[0]);
      formData.append('certificate', certificateFiles[0]);
      formData.append('password', certificatePassword);
      formData.append('visible', includeVisualSignature);
      if (includeVisualSignature) {
        formData.append('page', page);
        formData.append('position', signaturePosition);
      }

      const res = await fetch(`${API_BASE}/sign/pdf`, { method: 'POST', body: formData });

      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.message || 'Erro ao assinar PDF');
      }

      const blob = await res.blob();
      const filename = res.headers.get('Content-Disposition')?.match(/filename="?(.+?)"?$/)?.[1]
        || documentsToSign[0].name.replace('.pdf', '_signed.pdf');
      downloadBlob(blob, filename);
      setDone(`O PDF "${filename}" foi assinado (PAdES) e baixado para o seu computador.`);
    } else {
      const formData = new FormData();
      documentsToSign.forEach(f => formData.append('documents', f));
      formData.append('certificate', certificateFiles[0]);
      formData.append('password', certificatePassword);
      formData.append('visible', includeVisualSignature);
      if (includeVisualSignature) {
        formData.append('page', page);
        formData.append('position', signaturePosition);
      }

      const res = await fetch(`${API_BASE}/sign/pdf/batch`, { method: 'POST', body: formData });

      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.message || 'Erro ao assinar PDFs');
      }

      const blob = await res.blob();
      const signed = res.headers.get('X-Signed-Count') || documentsToSign.length;
      const failed = res.headers.get('X-Failed-Count') || '0';
      downloadBlob(blob, 'documentos_assinados.zip');
      if (failed > 0) {
        setStatus(`${signed} arquivo(s) assinado(s), ${failed} falha(s). ZIP baixado.`, 'error');
      } else {
        setDone(`${signed} documento(s) assinado(s) (PAdES) e baixados em documentos_assinados.zip.`);
      }
    }
  }

  async function signP7s() {
    if (documentsToSign.length === 1) {
      const formData = new FormData();
      formData.append('document', documentsToSign[0]);
      formData.append('certificate', certificateFiles[0]);
      formData.append('password', certificatePassword);

      const res = await fetch(`${API_BASE}/sign`, { method: 'POST', body: formData });

      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.message || 'Erro ao assinar documento');
      }

      const blob = await res.blob();
      const filename = documentsToSign[0].name + '.p7s';
      downloadBlob(blob, filename);
      setDone(`A assinatura destacada "${filename}" foi gerada e baixada.`);
    } else {
      const formData = new FormData();
      documentsToSign.forEach(f => formData.append('documents', f));
      formData.append('certificate', certificateFiles[0]);
      formData.append('password', certificatePassword);

      const res = await fetch(`${API_BASE}/sign/batch`, { method: 'POST', body: formData });

      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.message || 'Erro ao assinar documentos');
      }

      const data = await res.json();
      // Download each signature as individual .p7s files
      for (const doc of data.documents) {
        if (doc.success && doc.signature) {
          const bytes = Uint8Array.from(atob(doc.signature), c => c.charCodeAt(0));
          const blob = new Blob([bytes], { type: 'application/octet-stream' });
          downloadBlob(blob, doc.filename + '.p7s');
        }
      }
      if (data.signed === data.total) {
        setDone(`${data.signed} assinatura(s) destacada(s) (.p7s) gerada(s) e baixada(s).`);
      } else {
        setStatus(`${data.signed} de ${data.total} arquivo(s) assinado(s).`, 'error');
      }
    }
  }

  // page 0 = sentinel for "last page" (resolved server-side)
  $: page = pageMode === 'last' ? 0 : pageMode === 'first' ? 1 : (Number(customPage) || 1);
  $: isMultipleFiles = documentsToSign.length > 1;
  $: isPades = outputFormat === 'pades';
  $: certName = certificateFiles.length > 0 ? certificateFiles.map(f => f.name).join(', ') : '';
  $: docNames = documentsToSign.length > 0 ? documentsToSign.map(f => f.name).join(', ') : '';
</script>

<div class="page">
  <!-- ── Top bar ─────────────────────────────────────────────── -->
  <header class="topbar">
    <a class="brand" href={SYSTEM_URL} target="_blank" rel="noopener">
      <img src={logo} alt="ProcStudio" class="brand-logo brand-logo--light" />
      <img src={logoWhite} alt="ProcStudio" class="brand-logo brand-logo--dark" />
    </a>
    <div class="topbar-actions">
      <a class="ghost-link" href={SYSTEM_URL} target="_blank" rel="noopener">
        Sistema ProcStudio
      </a>
      <button
        class="theme-toggle"
        on:click={toggleTheme}
        aria-label={dark ? 'Mudar para tema claro' : 'Mudar para tema escuro'}
        title={dark ? 'Tema claro' : 'Tema escuro'}
      >
        {#if dark}
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="4.2" />
            <path d="M12 2v2.5M12 19.5V22M4.2 4.2l1.8 1.8M18 18l1.8 1.8M2 12h2.5M19.5 12H22M4.2 19.8l1.8-1.8M18 6l1.8-1.8" />
          </svg>
        {:else}
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M20 14.5A8 8 0 1 1 9.5 4 6.5 6.5 0 0 0 20 14.5Z" />
          </svg>
        {/if}
      </button>
    </div>
  </header>

  <!-- ── Hero: pitch + tool ──────────────────────────────────── -->
  <main class="hero">
    <section class="pitch">
      <span class="eyebrow">
        <span class="dot"></span> Assinatura digital · ICP-Brasil
      </span>
      <h1>
        Assine seus documentos com o<br />
        <em>certificado A1</em>, sem complicação.
      </h1>
      <p class="lead">
        Carregue o certificado, escolha o arquivo e assine em PDF (PAdES) ou em
        arquivo destacado (.p7s). Feito para advogados que querem praticidade, não
        burocracia técnica.
      </p>
      <ul class="trust">
        <li>
          <svg class="chk" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6.5 9.5 17 4 11.5" /></svg>
          Padrão ICP-Brasil — PAdES e PKCS#7 (.p7s)
        </li>
        <li>
          <svg class="chk" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6.5 9.5 17 4 11.5" /></svg>
          Assinatura visível, na página e posição que você escolher
        </li>
        <li>
          <svg class="chk" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6.5 9.5 17 4 11.5" /></svg>
          Rápido: assine em poucos segundos, direto no navegador
        </li>
      </ul>
    </section>

    <!-- Tool card -->
    <section class="tool" aria-label="Assinador de documentos">
      {#if view === 'form'}
        <div class="tool-head">
          <h2>Assinador de Documentos</h2>
          <p>Três passos. Nada além disso.</p>
        </div>

        <!-- Step 1: certificate -->
        <div class="step">
          <span class="step-no">1</span>
          <div class="step-body">
            <h3>Certificado A1</h3>

            <label class="field-label" for="cert-file">Arquivo do certificado (.pfx / .p12)</label>
            <button
              type="button"
              class="filepick"
              class:has-file={certName}
              id="cert-file"
              on:click={() => certificateInput.click()}
            >
              <svg class="doc" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 2.5h7l5 5V21a.5.5 0 0 1-.5.5H6.5A.5.5 0 0 1 6 21z" /><path d="M13 2.5V8h5" /></svg>
              <span class="filepick-text">{certName || 'Selecionar certificado...'}</span>
              <span class="filepick-cta">Procurar</span>
            </button>
            <input
              bind:this={certificateInput}
              type="file"
              accept=".pfx,.p12"
              on:change={handleCertificateUpload}
              hidden
            />

            <label class="field-label" for="cert-password">Senha do certificado</label>
            <div class="input-row">
              <input
                id="cert-password"
                class="text-input"
                type="password"
                bind:value={certificatePassword}
                placeholder="Insira a senha"
              />
              <button class="btn btn-ghost" on:click={checkCertificate} disabled={isLoading}>
                {isLoading ? 'Verificando...' : 'Verificar'}
              </button>
            </div>

            {#if certificateInfo && !certificateInfo.expired}
              <div class="cert-info">
                <div><span>Titular</span>{certificateInfo.commonName}</div>
                <div><span>Emissor</span>{certificateInfo.issuer}</div>
                <div><span>Válido até</span>{new Date(certificateInfo.notAfter).toLocaleDateString('pt-BR')}</div>
              </div>
            {/if}
          </div>
        </div>

        <!-- Step 2: format -->
        <div class="step">
          <span class="step-no">2</span>
          <div class="step-body">
            <h3>Formato de saída</h3>

            <div class="choice-grid">
              <label class="choice" class:selected={outputFormat === 'pades'}>
                <input type="radio" bind:group={outputFormat} value="pades" />
                <span class="choice-title">PDF assinado (PAdES)</span>
                <span class="choice-sub">Recomendado · assinatura embutida no PDF</span>
              </label>
              <label class="choice" class:selected={outputFormat === 'p7s'}>
                <input type="radio" bind:group={outputFormat} value="p7s" />
                <span class="choice-title">Assinatura destacada</span>
                <span class="choice-sub">Arquivo .p7s separado (PKCS#7)</span>
              </label>
            </div>

            {#if isPades}
              <div class="visual-box">
                <label class="switch-label">
                  <input type="checkbox" bind:checked={includeVisualSignature} />
                  <span>Incluir assinatura visível no documento</span>
                </label>

                {#if includeVisualSignature}
                  <div class="visual-fields">
                    <div class="inline-field">
                      <label for="page-mode">Página</label>
                      <select id="page-mode" bind:value={pageMode}>
                        <option value="last">Última página</option>
                        <option value="first">Primeira página</option>
                        <option value="custom">Específica…</option>
                      </select>
                    </div>
                    {#if pageMode === 'custom'}
                      <div class="inline-field">
                        <label for="page-num">Nº</label>
                        <input id="page-num" class="mini-input" type="number" min="1" bind:value={customPage} />
                      </div>
                    {/if}
                    <div class="inline-field">
                      <label for="position">Posição</label>
                      <select id="position" bind:value={signaturePosition}>
                        <option value="top-left">Superior esquerdo</option>
                        <option value="top-right">Superior direito</option>
                        <option value="bottom-left">Inferior esquerdo</option>
                        <option value="bottom-right">Inferior direito</option>
                      </select>
                    </div>
                  </div>
                {/if}
              </div>
            {/if}
          </div>
        </div>

        <!-- Step 3: documents -->
        <div class="step">
          <span class="step-no">3</span>
          <div class="step-body">
            <h3>Documentos</h3>

            <label class="field-label" for="doc-files">
              {isPades ? 'PDF para assinar' : 'Arquivo para assinar'}
            </label>
            <button
              type="button"
              class="filepick"
              class:has-file={docNames}
              id="doc-files"
              on:click={() => documentInput.click()}
            >
              <svg class="doc" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 2.5h7l5 5V21a.5.5 0 0 1-.5.5H6.5A.5.5 0 0 1 6 21z" /><path d="M13 2.5V8h5" /></svg>
              <span class="filepick-text">{docNames || 'Selecionar documento...'}</span>
              <span class="filepick-cta">Procurar</span>
            </button>
            <input
              bind:this={documentInput}
              type="file"
              accept={isPades ? '.pdf' : '*'}
              on:change={handleDocumentUpload}
              hidden
            />

            <button class="btn btn-primary btn-block" on:click={signDocuments} disabled={isLoading}>
              {#if isLoading}
                <span class="spinner" aria-hidden="true"></span> Assinando...
              {:else}
                Assinar documento
              {/if}
            </button>

            <p class="batch-hint">
              Precisa assinar vários documentos de uma vez?
              <a href={SYSTEM_URL} target="_blank" rel="noopener">Use o sistema ProcStudio</a>.
            </p>
          </div>
        </div>

        {#if statusMessage}
          <div class="status status-{statusType}" role="status">{statusMessage}</div>
        {/if}
      {:else}
        <!-- ── Success / done ──────────────────────────────────── -->
        <div class="done">
          <div class="done-badge">
            <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M5 12.5l4.5 4.5L19 7" />
            </svg>
          </div>
          <h2>Pronto, assinado!</h2>
          <p class="done-summary">{doneSummary}</p>

          <div class="cta">
            <h4>Conheça o sistema ProcStudio</h4>
            <p>
              Salve seu certificado e suas configurações e assine sem refazer tudo a
              cada documento. E muito mais:
            </p>
            <ul class="cta-features">
              <li><svg class="spark" viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><path d="M12 2c.5 3.9 2.1 5.5 6 6-3.9.5-5.5 2.1-6 6-.5-3.9-2.1-5.5-6-6 3.9-.5 5.5-2.1 6-6Z" /></svg>Geração de Documentos</li>
              <li><svg class="spark" viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><path d="M12 2c.5 3.9 2.1 5.5 6 6-3.9.5-5.5 2.1-6 6-.5-3.9-2.1-5.5-6-6 3.9-.5 5.5-2.1 6-6Z" /></svg>IA Integrada</li>
              <li><svg class="spark" viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><path d="M12 2c.5 3.9 2.1 5.5 6 6-3.9.5-5.5 2.1-6 6-.5-3.9-2.1-5.5-6-6 3.9-.5 5.5-2.1 6-6Z" /></svg>Onboarding pelo Cliente</li>
              <li><svg class="spark" viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><path d="M12 2c.5 3.9 2.1 5.5 6 6-3.9.5-5.5 2.1-6 6-.5-3.9-2.1-5.5-6-6 3.9-.5 5.5-2.1 6-6Z" /></svg>Assinatura em lote</li>
            </ul>
            <a class="btn btn-primary" href={SYSTEM_URL} target="_blank" rel="noopener">
              Conhecer o ProcStudio →
            </a>
          </div>

          <button class="btn btn-ghost btn-block" on:click={signAnother}>
            Assinar outro documento
          </button>
        </div>
      {/if}
    </section>
  </main>

  <footer class="footer">
    <span>ProcStudio · Assinador de Documentos A1</span>
    <a href={SYSTEM_URL} target="_blank" rel="noopener">procstudio.com.br</a>
  </footer>
</div>

<style>
  .page {
    min-height: 100vh;
    display: flex;
    flex-direction: column;
    width: min(1180px, 100%);
    margin: 0 auto;
    padding: clamp(1rem, 3vw, 2rem);
  }

  /* ── Top bar ───────────────────────────────────────────────── */
  .topbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    padding-block: 0.5rem 1.5rem;
  }
  .brand-logo {
    height: 42px;
    width: auto;
    display: block;
  }
  .brand-logo--dark {
    display: none;
    height: 32px;
  }
  @media (prefers-color-scheme: dark) {
    :root:not([data-theme='light']) .brand-logo--light {
      display: none;
    }
    :root:not([data-theme='light']) .brand-logo--dark {
      display: block;
    }
  }
  :root[data-theme='dark'] .brand-logo--light {
    display: none;
  }
  :root[data-theme='dark'] .brand-logo--dark {
    display: block;
  }
  .topbar-actions {
    display: flex;
    align-items: center;
    gap: 0.6rem;
  }
  .ghost-link {
    font-size: 0.85rem;
    font-weight: 600;
    color: var(--text-muted);
    text-decoration: none;
    padding: 0.45rem 0.8rem;
    border-radius: 999px;
    transition: color 0.2s, background 0.2s;
  }
  .ghost-link:hover {
    color: var(--brand-ink);
    background: var(--brand-soft);
  }
  .theme-toggle {
    display: grid;
    place-items: center;
    width: 38px;
    height: 38px;
    border-radius: 999px;
    border: 1px solid var(--border);
    background: var(--surface);
    color: var(--text-muted);
    cursor: pointer;
    transition: color 0.2s, border-color 0.2s, transform 0.2s;
  }
  .theme-toggle:hover {
    color: var(--brand-ink);
    border-color: var(--brand-border);
    transform: rotate(-12deg);
  }

  /* ── Hero ──────────────────────────────────────────────────── */
  .hero {
    flex: 1;
    display: grid;
    grid-template-columns: 1.05fr 1fr;
    gap: clamp(1.5rem, 4vw, 4rem);
    align-items: center;
    padding-block: clamp(1rem, 4vw, 3rem);
  }

  .eyebrow {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.8rem;
    font-weight: 600;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--brand-ink);
    background: var(--brand-soft);
    border: 1px solid var(--brand-border);
    padding: 0.35rem 0.8rem;
    border-radius: 999px;
  }
  .eyebrow .dot {
    width: 7px;
    height: 7px;
    border-radius: 999px;
    background: var(--brand);
    box-shadow: 0 0 0 3px var(--brand-soft);
  }

  h1 {
    font-family: var(--font-display);
    font-optical-sizing: auto;
    font-weight: 600;
    font-size: clamp(2.1rem, 5vw, 3.4rem);
    line-height: 1.07;
    letter-spacing: -0.02em;
    color: var(--text);
    margin: 1.2rem 0 0;
  }
  h1 em {
    font-style: normal;
    font-weight: 700;
    color: var(--brand-ink);
  }
  .lead {
    font-size: clamp(1rem, 1.4vw, 1.15rem);
    color: var(--text-muted);
    max-width: 46ch;
    margin: 1.2rem 0 0;
  }
  .trust {
    list-style: none;
    padding: 0;
    margin: 1.8rem 0 0;
    display: grid;
    gap: 0.7rem;
  }
  .trust li {
    display: flex;
    align-items: center;
    gap: 0.7rem;
    font-size: 0.95rem;
    color: var(--text);
    font-weight: 500;
  }
  .trust :global(svg.chk) {
    flex: none;
    color: var(--brand-ink);
  }

  /* ── Tool card ─────────────────────────────────────────────── */
  .tool {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: calc(var(--radius) + 4px);
    box-shadow: var(--shadow-lg);
    padding: clamp(1.4rem, 2.6vw, 2.2rem);
    position: relative;
    overflow: hidden;
  }
  .tool::before {
    content: '';
    position: absolute;
    inset: 0 0 auto 0;
    height: 4px;
    background: linear-gradient(90deg, var(--brand), #36a8ff 60%, transparent);
  }
  .tool-head h2,
  .done h2 {
    font-family: var(--font-display);
    font-weight: 600;
    font-size: 1.5rem;
    letter-spacing: -0.01em;
    color: var(--text);
    margin: 0;
  }
  .tool-head p {
    margin: 0.25rem 0 1.5rem;
    color: var(--text-subtle);
    font-size: 0.92rem;
  }

  /* Steps */
  .step {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: 0.9rem;
    padding-block: 1.1rem;
    border-top: 1px solid var(--border);
  }
  .step-body {
    min-width: 0; /* allow the content column to shrink so children don't overflow the card */
  }
  .step:first-of-type {
    border-top: none;
    padding-top: 0;
  }
  .step-no {
    display: grid;
    place-items: center;
    width: 26px;
    height: 26px;
    border-radius: 999px;
    background: var(--brand-soft);
    color: var(--brand-ink);
    border: 1px solid var(--brand-border);
    font-size: 0.8rem;
    font-weight: 700;
    margin-top: 2px;
  }
  .step-body h3 {
    margin: 0 0 0.8rem;
    font-size: 1.02rem;
    font-weight: 600;
    color: var(--text);
  }

  .field-label {
    display: block;
    font-size: 0.82rem;
    font-weight: 600;
    color: var(--text-muted);
    margin: 0.9rem 0 0.35rem;
  }
  .field-label:first-child {
    margin-top: 0;
  }

  /* File picker */
  .filepick {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.7rem 0.7rem 0.7rem 0.85rem;
    border: 1.5px dashed var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-soft);
    color: var(--text-subtle);
    font: inherit;
    font-size: 0.9rem;
    cursor: pointer;
    text-align: left;
    transition: border-color 0.2s, background 0.2s, color 0.2s;
  }
  .filepick:hover {
    border-color: var(--brand-border);
    color: var(--text-muted);
  }
  .filepick.has-file {
    border-style: solid;
    border-color: var(--brand-border);
    background: var(--brand-soft);
    color: var(--text);
  }
  .filepick :global(svg.doc) {
    flex: none;
    color: var(--brand-ink);
  }
  .filepick-text {
    flex: 1;
    min-width: 0; /* allow ellipsis instead of forcing the picker wider than the card */
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .filepick-cta {
    flex: none;
    font-size: 0.8rem;
    font-weight: 600;
    color: var(--brand-ink);
    background: var(--surface);
    border: 1px solid var(--brand-border);
    padding: 0.3rem 0.7rem;
    border-radius: 999px;
  }

  /* Inputs */
  .input-row {
    display: flex;
    gap: 0.5rem;
  }
  .text-input {
    flex: 1;
    min-width: 0; /* let the input shrink in flex rows so the row never overflows */
    padding: 0.65rem 0.8rem;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    font: inherit;
    font-size: 0.9rem;
    background: var(--surface);
    color: var(--text);
    transition: border-color 0.2s, box-shadow 0.2s;
  }
  .text-input::placeholder {
    color: var(--text-subtle);
  }
  .text-input:focus,
  .mini-input:focus,
  select:focus {
    outline: none;
    border-color: var(--brand);
    box-shadow: 0 0 0 4px var(--ring);
  }

  /* Cert info */
  .cert-info {
    margin-top: 0.9rem;
    display: grid;
    gap: 0.35rem;
    padding: 0.85rem 1rem;
    background: var(--ok-bg);
    border: 1px solid var(--ok-border);
    border-radius: var(--radius-sm);
    font-size: 0.85rem;
    color: var(--text);
  }
  .cert-info div {
    display: flex;
    gap: 0.5rem;
  }
  .cert-info span {
    flex: none;
    width: 84px;
    color: var(--ok-text);
    font-weight: 600;
  }

  /* Format choices */
  .choice-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.7rem;
  }
  .choice {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 0.2rem;
    padding: 0.85rem 0.9rem;
    border: 1.5px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-soft);
    cursor: pointer;
    transition: border-color 0.2s, background 0.2s;
  }
  .choice:hover {
    border-color: var(--brand-border);
  }
  .choice.selected {
    border-color: var(--brand);
    background: var(--brand-soft);
  }
  .choice input {
    position: absolute;
    opacity: 0;
    pointer-events: none;
  }
  .choice-title {
    font-size: 0.92rem;
    font-weight: 600;
    color: var(--text);
  }
  .choice-sub {
    font-size: 0.78rem;
    color: var(--text-subtle);
    line-height: 1.35;
  }

  /* Visual signature */
  .visual-box {
    margin-top: 0.9rem;
    padding: 0.9rem 1rem;
    border: 1px solid var(--brand-border);
    border-radius: var(--radius-sm);
    background: var(--brand-soft);
  }
  .switch-label {
    display: flex;
    align-items: center;
    gap: 0.55rem;
    font-size: 0.9rem;
    font-weight: 500;
    color: var(--text);
    cursor: pointer;
  }
  .visual-fields {
    display: flex;
    flex-wrap: wrap;
    gap: 1.2rem;
    margin-top: 0.9rem;
    padding-left: 1.7rem;
  }
  .inline-field {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.85rem;
    color: var(--text-muted);
  }
  .mini-input {
    width: 64px;
    padding: 0.4rem 0.5rem;
    border: 1px solid var(--border-strong);
    border-radius: 8px;
    font: inherit;
    font-size: 0.85rem;
    background: var(--surface);
    color: var(--text);
  }
  select {
    padding: 0.4rem 0.5rem;
    border: 1px solid var(--border-strong);
    border-radius: 8px;
    font: inherit;
    font-size: 0.85rem;
    background: var(--surface);
    color: var(--text);
    cursor: pointer;
  }

  input[type='radio'],
  input[type='checkbox'] {
    accent-color: var(--brand);
  }

  /* Buttons */
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    border: none;
    border-radius: var(--radius-sm);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.2s, color 0.2s, border-color 0.2s, transform 0.05s;
    text-decoration: none;
  }
  .btn:active {
    transform: translateY(1px);
  }
  .btn-primary {
    padding: 0.8rem 1.5rem;
    background: var(--brand);
    color: #fff;
    font-size: 0.98rem;
    box-shadow: 0 8px 20px -10px var(--brand);
  }
  .btn-primary:hover {
    background: var(--brand-hover);
  }
  .btn-ghost {
    padding: 0.65rem 1.1rem;
    background: transparent;
    color: var(--text-muted);
    border: 1px solid var(--border-strong);
    font-size: 0.9rem;
    white-space: nowrap;
  }
  .btn-ghost:hover {
    color: var(--brand-ink);
    border-color: var(--brand-border);
    background: var(--brand-soft);
  }
  .btn-block {
    width: 100%;
    margin-top: 1.1rem;
  }
  .batch-hint {
    margin: 0.8rem 0 0;
    font-size: 0.8rem;
    color: var(--text-subtle);
    text-align: center;
  }
  .batch-hint a {
    color: var(--brand-ink);
    font-weight: 600;
    text-decoration: none;
  }
  .batch-hint a:hover {
    text-decoration: underline;
  }
  .btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .spinner {
    width: 15px;
    height: 15px;
    border: 2px solid rgba(255, 255, 255, 0.45);
    border-top-color: #fff;
    border-radius: 999px;
    animation: spin 0.7s linear infinite;
  }
  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  /* Status */
  .status {
    margin-top: 1.2rem;
    padding: 0.8rem 1rem;
    border-radius: var(--radius-sm);
    font-size: 0.88rem;
    border: 1px solid;
  }
  .status-info {
    background: var(--brand-soft);
    border-color: var(--brand-border);
    color: var(--text);
  }
  .status-success {
    background: var(--ok-bg);
    border-color: var(--ok-border);
    color: var(--ok-text);
  }
  .status-error {
    background: var(--err-bg);
    border-color: var(--err-border);
    color: var(--err-text);
  }

  /* Done view */
  .done {
    text-align: center;
    padding-block: 0.5rem;
  }
  .done-badge {
    width: 64px;
    height: 64px;
    margin: 0 auto 1.1rem;
    display: grid;
    place-items: center;
    border-radius: 999px;
    background: var(--ok-bg);
    color: var(--ok-text);
    border: 1px solid var(--ok-border);
    animation: pop 0.4s cubic-bezier(0.2, 0.8, 0.2, 1);
  }
  @keyframes pop {
    from { transform: scale(0.7); opacity: 0; }
    to { transform: scale(1); opacity: 1; }
  }
  .done-summary {
    color: var(--text-muted);
    font-size: 0.95rem;
    max-width: 42ch;
    margin: 0.5rem auto 0;
  }
  .cta {
    margin: 1.6rem 0 1rem;
    padding: 1.3rem;
    text-align: left;
    border-radius: var(--radius);
    border: 1px solid var(--brand-border);
    background:
      radial-gradient(120% 120% at 100% 0%, var(--brand-soft), transparent 70%),
      var(--surface-soft);
  }
  .cta h4 {
    font-family: var(--font-display);
    font-size: 1.15rem;
    font-weight: 600;
    margin: 0 0 0.4rem;
    color: var(--text);
  }
  .cta p {
    margin: 0 0 1rem;
    font-size: 0.9rem;
    color: var(--text-muted);
  }
  .cta-features {
    list-style: none;
    padding: 0;
    margin: 0 0 1.3rem;
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.6rem 1rem;
  }
  .cta-features li {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.88rem;
    font-weight: 500;
    color: var(--text);
  }
  .cta-features :global(svg.spark) {
    flex: none;
    color: var(--brand-ink);
  }

  /* Footer */
  .footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    margin-top: 2rem;
    padding-top: 1.2rem;
    border-top: 1px solid var(--border);
    font-size: 0.8rem;
    color: var(--text-subtle);
  }
  .footer a {
    color: var(--text-muted);
    text-decoration: none;
    font-weight: 600;
  }
  .footer a:hover {
    color: var(--brand-ink);
  }

  /* ── Responsive ────────────────────────────────────────────── */
  @media (max-width: 880px) {
    .hero {
      grid-template-columns: 1fr;
      gap: 2rem;
      padding-block: 1rem 0;
    }
    .pitch {
      text-align: center;
    }
    .eyebrow {
      margin-inline: auto;
    }
    .lead {
      margin-inline: auto;
    }
    .trust {
      max-width: 26rem;
      margin-inline: auto;
      text-align: left;
    }
    h1 br {
      display: none;
    }
  }

  @media (max-width: 520px) {
    .ghost-link {
      display: none;
    }
    .choice-grid {
      grid-template-columns: 1fr;
    }
    /* Password + "Verificar" stack instead of getting squeezed off-card */
    .input-row {
      flex-direction: column;
      align-items: stretch;
    }
    .input-row .btn {
      width: 100%;
    }
    /* Visual-signature controls go full width and drop the indent */
    .visual-fields {
      flex-direction: column;
      gap: 0.8rem;
      padding-left: 0;
    }
    .inline-field {
      width: 100%;
    }
    .inline-field select,
    .inline-field .mini-input {
      flex: 1;
      min-width: 0;
    }
    .footer {
      flex-direction: column;
      gap: 0.4rem;
      text-align: center;
    }
    .trust li {
      font-size: 0.88rem;
    }
  }

  /* Tighten the CTA feature grid on the smallest phones */
  @media (max-width: 380px) {
    .cta-features {
      grid-template-columns: 1fr;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
      animation-duration: 0.001ms !important;
      transition-duration: 0.001ms !important;
    }
  }
</style>
