<script>
  import { onMount } from 'svelte';
  import logo from './assets/logo.png';
  import logoWhite from './assets/logo-white.svg';

  const API_BASE = '/api/v1';
  const SYSTEM_URL = 'https://procstudio.com.br';

  // ═══ DEBUG PANEL (oculto — trocar para true p/ inspecionar payloads) ═══
  const DEBUG = false;
  let debugLog = [];
  let debugOpen = true;

  function fmtBody(body) {
    if (body instanceof FormData) {
      const parts = [];
      for (const [k, v] of body.entries()) {
        if (v instanceof File) parts.push(`${k}: <file "${v.name}" ${v.size}b ${v.type || '?'}>`);
        else if (k.toLowerCase().includes('password')) parts.push(`${k}: ${'•'.repeat(String(v).length)} (${String(v).length} chars)`);
        else parts.push(`${k}: ${v}`);
      }
      return parts.join('\n');
    }
    if (typeof body === 'string') return body;
    return body ? String(body) : '(sem body)';
  }

  onMount(() => {
    if (!DEBUG) return;
    const orig = window.fetch;
    window.fetch = async (input, init = {}) => {
      const url = typeof input === 'string' ? input : (input && input.url) || '';
      if (!url.includes('/api/')) return orig(input, init);
      const entry = {
        time: new Date().toLocaleTimeString(),
        method: (init.method || 'GET').toUpperCase(),
        url,
        req: fmtBody(init.body),
        status: '…',
        res: '(aguardando)',
      };
      debugLog = [entry, ...debugLog].slice(0, 25);
      try {
        const resp = await orig(input, init);
        entry.status = resp.status;
        const ct = resp.headers.get('content-type') || '';
        const clone = resp.clone();
        if (ct.includes('json') || ct.includes('text')) {
          const txt = await clone.text();
          try { entry.res = JSON.stringify(JSON.parse(txt), null, 2); }
          catch { entry.res = txt; }
        } else {
          const buf = await clone.arrayBuffer();
          entry.res = `<binário: ${ct || 'tipo?'} — ${buf.byteLength} bytes>`;
        }
        debugLog = [...debugLog];
        return resp;
      } catch (e) {
        entry.status = 'ERRO';
        entry.res = String(e);
        debugLog = [...debugLog];
        throw e;
      }
    };
  });
  // ═══════════════════════════════════════════════════════════════════

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

  // ── Modo: assinar | verificar ──────────────────────────────────
  let mode = 'sign'; // 'sign' | 'verify'
  let verifyFiles = [];
  let verifyInput;
  let verifyResult = null;

  function switchMode(m) {
    if (mode === m) return;
    mode = m;
    statusMessage = '';
    verifyResult = null;
  }

  function handleVerifyUpload(event) {
    verifyFiles = Array.from(event.target.files);
    verifyResult = null;
    statusMessage = '';
  }

  async function verifyDocument() {
    if (verifyFiles.length === 0) {
      setStatus('Selecione um PDF assinado para verificar.', 'error');
      return;
    }
    isLoading = true;
    verifyResult = null;
    setStatus(`Verificando ${verifyFiles[0].name}...`);
    try {
      const formData = new FormData();
      formData.append('document', verifyFiles[0]);
      const res = await fetch(`${API_BASE}/verify/pdf`, { method: 'POST', body: formData });
      const data = await res.json();
      if (!res.ok) {
        setStatus(data.error || data.message || 'Não foi possível verificar o documento.', 'error');
      } else {
        verifyResult = data;
        statusMessage = '';
      }
    } catch (err) {
      setStatus('Erro de conexão com o servidor.', 'error');
    } finally {
      isLoading = false;
    }
  }

  // ── Helpers de exibição da verificação ─────────────────────────
  function formatCpf(cpf) {
    if (!cpf || cpf.length !== 11) return cpf;
    return `${cpf.slice(0, 3)}.${cpf.slice(3, 6)}.${cpf.slice(6, 9)}-${cpf.slice(9)}`;
  }
  function fmtDate(s) {
    if (!s) return '—';
    const d = new Date(s);
    return isNaN(d.getTime()) ? s : d.toLocaleString('pt-BR');
  }
  function revLabel(rev) {
    if (!rev) return '—';
    switch (rev.state) {
      case 'GOOD': return '✓ não revogado';
      case 'REVOKED': return '✗ REVOGADO';
      case 'NOT_CHECKED': return 'não verificada';
      default: return rev.state;
    }
  }
  function badgeClass(type) {
    return type === 'GOV_BR' ? 'badge-govbr' : type === 'ICP_BRASIL' ? 'badge-icp' : 'badge-other';
  }

  let coverInfoOpen = false;

  // "Cobre o documento": verde SIM se cobre; se NÃO cobre mas uma assinatura
  // POSTERIOR cobre o arquivo inteiro, é normal (multi-assinatura) → neutro, sem X.
  // Só é problema (vermelho ✗) quando nada posterior cobre o documento.
  function coverDisplay(sig) {
    if (!verifyResult) return { text: '—', cls: '' };
    if (sig.coversWholeDocument) return { text: '✓ SIM', cls: 'v-ok' };
    const laterCovers = verifyResult.signatures.some(
      (o) => o.index > sig.index && o.coversWholeDocument
    );
    return laterCovers ? { text: 'NÃO', cls: 'v-neutral' } : { text: '✗ NÃO', cls: 'v-bad' };
  }

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
  $: verifyName = verifyFiles.length > 0 ? verifyFiles[0].name : '';
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
        <span class="dot"></span> Assinatura e verificação digital · ICP-Brasil e Gov.BR
      </span>
      <h1>
        Assine e Verifique seus documentos com<br />
        certificado <em>A1</em> e
        <span class="govbr-mark"><span class="gov">Gov.</span><span class="b">B</span><span class="r">R</span></span>.
      </h1>
      <p class="lead">
        Assine em PDF (PAdES) ou arquivo destacado (.p7s), e verifique a validade
        de documentos assinados — por certificado A1 (ICP-Brasil) ou pelo Gov.BR.
        Feito para advogados que querem praticidade, não burocracia técnica.
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
    <section class="tool" class:verify-mode={mode === 'verify'} aria-label="Assinador e verificador de documentos">
      {#if view === 'form'}
        <!-- ── Switch: Assinar / Verificar ──────────────────────── -->
        <div class="mode-switch" role="tablist" aria-label="Escolha a ação">
          <button type="button" role="tab" class="mode-btn" class:active={mode === 'sign'} aria-selected={mode === 'sign'} on:click={() => switchMode('sign')}>
            <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h4L18.5 9.5a2 2 0 0 0-2.8-2.8L5 17.2z" /><path d="M13.5 6.5l4 4" /></svg>
            Assinar
          </button>
          <button type="button" role="tab" class="mode-btn" class:active={mode === 'verify'} aria-selected={mode === 'verify'} on:click={() => switchMode('verify')}>
            <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l7 3v5c0 4.5-3 7.6-7 9-4-1.4-7-4.5-7-9V6z" /><path d="M9 12l2 2 4-4" /></svg>
            Verificar
          </button>
        </div>

        {#if mode === 'sign'}
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
                autocomplete="off"
                autocapitalize="off"
                autocorrect="off"
                spellcheck="false"
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

        {:else}
          <!-- ── VERIFY MODE ─────────────────────────────────────── -->
          <div class="tool-head">
            <h2>Verificar Documentos</h2>
            <p>Confira se um documento assinado é válido — A1 (ICP-Brasil) ou Gov.BR.</p>
          </div>

          <div class="step">
            <span class="step-no">1</span>
            <div class="step-body">
              <h3>Documento assinado</h3>
              <label class="field-label" for="verify-file">PDF assinado (.pdf)</label>
              <button
                type="button"
                class="filepick"
                class:has-file={verifyName}
                id="verify-file"
                on:click={() => verifyInput.click()}
              >
                <svg class="doc" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 2.5h7l5 5V21a.5.5 0 0 1-.5.5H6.5A.5.5 0 0 1 6 21z" /><path d="M13 2.5V8h5" /></svg>
                <span class="filepick-text">{verifyName || 'Selecionar documento...'}</span>
                <span class="filepick-cta">Procurar</span>
              </button>
              <input
                bind:this={verifyInput}
                type="file"
                accept=".pdf"
                on:change={handleVerifyUpload}
                hidden
              />

              <button class="btn btn-govbr btn-block" on:click={verifyDocument} disabled={isLoading}>
                {#if isLoading}
                  <span class="spinner" aria-hidden="true"></span> Verificando...
                {:else}
                  Verificar assinatura
                {/if}
              </button>
            </div>
          </div>

          {#if verifyResult}
            <div class="vresult" class:vok={verifyResult.valid} class:vbad={!verifyResult.valid}>
              <div class="vresult-head">
                <span class="vresult-icon">
                  {#if verifyResult.valid}
                    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7" /></svg>
                  {:else}
                    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
                  {/if}
                </span>
                <div class="vresult-title">
                  <strong>{verifyResult.valid ? 'Assinatura válida' : 'Assinatura inválida'}</strong>
                  <span>{verifyResult.totalSignatures} assinatura(s){verifyResult.filename ? ' · ' + verifyResult.filename : ''}</span>
                </div>
              </div>

              {#each verifyResult.signatures as sig, i}
                {@const cov = coverDisplay(sig)}
                <div class="vsig">
                  <div class="vsig-top">
                    <span class="vsig-name">{sig.signerName || 'Signatário ' + (i + 1)}</span>
                    <span class="badge {badgeClass(sig.certificateType)}">{sig.certificateTypeLabel || sig.certificateType}</span>
                  </div>
                  <dl class="vsig-grid">
                    {#if sig.cpf}<div><dt>CPF</dt><dd>{formatCpf(sig.cpf)}</dd></div>{/if}
                    <div><dt>Assinado em</dt><dd>{fmtDate(sig.signingTime)}</dd></div>
                    <div><dt>Integridade</dt><dd class={sig.integrityValid ? 'v-ok' : 'v-bad'}>{sig.integrityValid ? '✓ íntegra' : '✗ alterada'}</dd></div>
                    <div><dt>Certificado</dt><dd class={sig.certificateValid ? 'v-ok' : 'v-bad'}>{sig.certificateValid ? '✓ válido' : '✗ inválido'}</dd></div>
                    <div>
                      <dt>Cobre o documento<button type="button" class="info-btn" on:click={() => coverInfoOpen = !coverInfoOpen} aria-label="O que significa cobrir o documento?">i</button></dt>
                      <dd class={cov.cls}>{cov.text}</dd>
                    </div>
                    <div><dt>Revogação</dt><dd>{revLabel(sig.revocation)}</dd></div>
                  </dl>
                </div>
              {/each}

              {#if coverInfoOpen}
                <div class="vcover-info">
                  <strong>"Cobre o documento"</strong> indica se a assinatura protege o arquivo
                  inteiro — se nada foi acrescentado depois dela. Em documentos com mais de uma
                  assinatura, as anteriores aparecem como "não" porque outra assinatura foi
                  adicionada em seguida: isso é <strong>normal</strong>, não é adulteração
                  (veja "Integridade"). Só é um problema quando a última assinatura não cobre
                  o documento.
                </div>
              {/if}

              <button class="btn btn-ghost btn-block" on:click={() => { verifyResult = null; verifyFiles = []; }}>
                Verificar outro documento
              </button>
            </div>
          {/if}
        {/if}

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

  <!-- ═══ DEBUG PANEL (oculto — const DEBUG no topo) ═══ -->
  {#if DEBUG}
  <div class="dbg" class:dbg-open={debugOpen}>
    <div class="dbg-bar" on:click={() => debugOpen = !debugOpen}>
      🐞 DEBUG payloads ({debugLog.length}) — clique p/ {debugOpen ? 'ocultar' : 'mostrar'}
      <button class="dbg-clear" on:click|stopPropagation={() => debugLog = []}>limpar</button>
    </div>
    {#if debugOpen}
      <div class="dbg-body">
        {#each debugLog as e}
          <div class="dbg-entry">
            <div class="dbg-head">
              <span class="dbg-status dbg-s{String(e.status)[0]}">{e.status}</span>
              <b>{e.method}</b> {e.url} <span class="dbg-time">{e.time}</span>
            </div>
            <div class="dbg-col">
              <div class="dbg-label">▶ REQUEST</div>
              <pre class="dbg-pre">{e.req}</pre>
            </div>
            <div class="dbg-col">
              <div class="dbg-label">◀ RESPONSE</div>
              <pre class="dbg-pre">{e.res}</pre>
            </div>
          </div>
        {:else}
          <div class="dbg-empty">Nenhuma chamada ainda. Use o formulário acima.</div>
        {/each}
      </div>
    {/if}
  </div>
  {/if}
  <!-- ═══ fim DEBUG PANEL ═══ -->

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
  .govbr-mark {
    font-weight: 700;
    white-space: nowrap;
  }
  .govbr-mark .gov {
    color: var(--govbr-logo-blue);
  }
  .govbr-mark .b {
    color: var(--govbr-logo-yellow);
  }
  .govbr-mark .r {
    color: var(--govbr-logo-green);
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

  /* ═══ VERIFY MODE + gov.br accent ═══════════════════════════════ */
  .btn-govbr {
    padding: 0.8rem 1.5rem;
    background: var(--govbr);
    color: var(--on-govbr);
    font-size: 0.98rem;
    box-shadow: 0 8px 20px -10px var(--govbr);
  }
  .btn-govbr:hover { background: var(--govbr-hover); }

  /* Card: borda-topo gov.br no modo verificar */
  .tool.verify-mode::before {
    background: linear-gradient(90deg, var(--govbr), #4785e0 60%, transparent);
  }

  /* Switch Assinar / Verificar */
  .mode-switch {
    display: flex;
    gap: 4px;
    padding: 4px;
    margin-bottom: 1.4rem;
    background: var(--surface-sunken);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .mode-btn {
    flex: 1;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.45rem;
    padding: 0.6rem 0.5rem;
    border: none;
    border-radius: calc(var(--radius-sm) - 3px);
    background: transparent;
    color: var(--text-subtle);
    font: inherit;
    font-weight: 600;
    font-size: 0.92rem;
    cursor: pointer;
    transition: background 0.15s, color 0.15s, box-shadow 0.15s;
  }
  .mode-btn:hover { color: var(--text); }
  .mode-btn.active {
    background: var(--surface);
    color: var(--brand-ink);
    box-shadow: var(--shadow-sm);
  }
  .mode-btn.active:last-child { color: var(--govbr-ink); } /* Verificar = gov.br */

  /* Resultado da verificação */
  .vresult {
    margin-top: 1.4rem;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .vresult-head {
    display: flex;
    align-items: center;
    gap: 0.8rem;
    padding: 1rem 1.1rem;
  }
  .vresult.vok .vresult-head { background: var(--ok-bg); border-bottom: 1px solid var(--ok-border); }
  .vresult.vbad .vresult-head { background: var(--err-bg); border-bottom: 1px solid var(--err-border); }
  .vresult-icon {
    display: inline-flex; align-items: center; justify-content: center;
    width: 40px; height: 40px; border-radius: 50%; flex-shrink: 0; color: #fff;
  }
  .vresult.vok .vresult-icon { background: var(--ok-text); }
  .vresult.vbad .vresult-icon { background: var(--err-text); }
  .vresult-title { display: flex; flex-direction: column; line-height: 1.3; }
  .vresult-title strong { font-size: 1.05rem; }
  .vresult.vok .vresult-title strong { color: var(--ok-text); }
  .vresult.vbad .vresult-title strong { color: var(--err-text); }
  .vresult-title span { font-size: 0.82rem; color: var(--text-subtle); word-break: break-all; }

  .vsig { padding: 0.9rem 1.1rem; border-top: 1px solid var(--border); }
  .vsig:first-of-type { border-top: none; }
  .vsig-top {
    display: flex; align-items: center; justify-content: space-between;
    gap: 0.5rem; margin-bottom: 0.7rem; flex-wrap: wrap;
  }
  .vsig-name { font-weight: 700; color: var(--text); }
  .vsig-grid { margin: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 0.55rem 1rem; }
  .vsig-grid > div { display: flex; flex-direction: column; }
  .vsig-grid dt { font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0.03em; color: var(--text-subtle); }
  .vsig-grid dd { margin: 0; font-size: 0.9rem; color: var(--text); text-transform: uppercase; }
  .vsig-grid dd.v-ok { color: var(--ok-text); font-weight: 600; }
  .vsig-grid dd.v-bad { color: var(--err-text); font-weight: 600; }
  .vsig-grid dd.v-neutral { color: var(--text-muted); font-weight: 600; }

  .info-btn {
    display: inline-flex; align-items: center; justify-content: center;
    width: 15px; height: 15px; margin-left: 5px; padding: 0;
    border: 1px solid var(--border-strong); border-radius: 50%;
    background: transparent; color: var(--text-subtle);
    font-size: 10px; font-weight: 700; font-style: italic; line-height: 1;
    text-transform: none; cursor: pointer; vertical-align: middle;
  }
  .info-btn:hover { color: var(--brand-ink); border-color: var(--brand-border); background: var(--brand-soft); }

  .vcover-info {
    margin: 0 1.1rem 0.9rem;
    padding: 0.7rem 0.85rem;
    background: var(--surface-sunken);
    border: 1px solid var(--border);
    border-left: 3px solid var(--border-strong);
    border-radius: var(--radius-sm);
    font-size: 0.82rem;
    color: var(--text-muted);
    line-height: 1.55;
  }
  .vcover-info strong { color: var(--text); font-weight: 700; }

  /* Badges de tipo de certificado */
  .badge {
    display: inline-block;
    padding: 0.2rem 0.6rem;
    border-radius: 999px;
    font-size: 0.72rem;
    font-weight: 700;
    border: 1px solid transparent;
    white-space: nowrap;
  }
  .badge-icp { background: var(--brand-soft); color: var(--brand-ink); border-color: var(--brand-border); }
  .badge-govbr { background: var(--govbr-soft); color: var(--govbr-ink); border-color: var(--govbr-border); }
  .badge-other { background: var(--surface-sunken); color: var(--text-muted); border-color: var(--border); }
  /* ═══════════════════════════════════════════════════════════════ */
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

  /* ═══ DEBUG PANEL (TEMPORÁRIO — remover depois) ═══ */
  .dbg {
    position: fixed; left: 0; right: 0; bottom: 0; z-index: 9999;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px; background: #0b0e14; color: #d6deeb;
    border-top: 2px solid #ff5555; box-shadow: 0 -4px 20px rgba(0,0,0,.5);
  }
  .dbg-bar {
    padding: 6px 12px; cursor: pointer; background: #ff5555; color: #0b0e14;
    font-weight: 700; display: flex; align-items: center; gap: 10px;
  }
  .dbg-clear {
    margin-left: auto; font: inherit; font-weight: 700; cursor: pointer;
    background: #0b0e14; color: #ff5555; border: none; padding: 2px 8px; border-radius: 4px;
  }
  .dbg-body { max-height: 42vh; overflow: auto; padding: 8px 12px; }
  .dbg-entry { border-bottom: 1px solid #1c2333; padding: 8px 0; }
  .dbg-head { margin-bottom: 4px; word-break: break-all; }
  .dbg-time { color: #5c6773; }
  .dbg-status { display: inline-block; min-width: 34px; text-align: center;
    padding: 0 6px; border-radius: 4px; font-weight: 700; margin-right: 6px; }
  .dbg-s2 { background: #2e7d32; color: #fff; }  /* 2xx */
  .dbg-s4 { background: #ef6c00; color: #fff; }  /* 4xx */
  .dbg-s5, .dbg-sE { background: #c62828; color: #fff; } /* 5xx / ERRO */
  .dbg-col { margin: 4px 0; }
  .dbg-label { color: #82aaff; font-weight: 700; font-size: 11px; }
  .dbg-pre {
    margin: 2px 0 0; white-space: pre-wrap; word-break: break-word;
    background: #11151f; padding: 6px 8px; border-radius: 4px; max-height: 220px; overflow: auto;
  }
  .dbg-empty { color: #5c6773; padding: 10px 0; }
  /* ═══ fim DEBUG PANEL ═══ */
</style>
