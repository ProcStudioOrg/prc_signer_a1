# Impacto nos Sistemas Consumidores — campos de tipo de certificado

> Mudança: `/verify/pdf` e `/certificate/info` passaram a devolver `cpf`,
> `certificateType` e `certificateTypeLabel`. Este doc responde: **algum sistema
> que se alimenta do assinador/verificador precisa mudar?**

## TL;DR — Não, nada precisa mudar. ✅

A mudança é **puramente aditiva**: nenhum campo existente foi removido ou
renomeado. Todo consumidor atual continua funcionando sem alteração.

## Consumidores mapeados (workspace ProcStudio)

### 1. Backend Rails — `ProcStudio-Docker/api`
`app/services/a1_signature/sign_document_service.rb`
- Usa **só endpoints de assinatura**: `POST /sign` e `POST /sign/pdf`.
- Lê apenas `error` / `message` em caso de falha.
- **Não** chama `/verify/pdf` nem `/certificate/info`.
- `ValidateCertificateService` valida o PKCS12 **localmente** (`ParsePkcs12Service`),
  não bate no assinador.
- **Impacto: NENHUM.** Respostas de assinatura não mudaram.

### 2. Frontend do assinador — `prc_signer_a1/frontend/src/App.svelte`
- Chama `/certificate/info` e mostra `data.commonName` + `data.expired`.
- Os campos novos (`certificateType…`) são simplesmente ignorados.
- **Impacto: NENHUM.** (Melhoria opcional abaixo.)

### 3. Frontend ProcStudio — `ProcStudio-Docker/frontend/.../ReviewSignersSection.svelte`
- Falso positivo na busca: tem uma função local `signerName(type)` sobre tipo de
  signatário (cliente/advogado), **não consome** a API do verificador.
- **Impacto: NENHUM.**

### 4. Coleção Bruno — `prc_collection/prc_signer_a1`
- Testes manuais. Docs atualizados para descrever os campos novos.

## Nenhum consumidor de `/verify/pdf` hoje

O endpoint de verificação só é exercido via Bruno / testes manuais. Logo, os
campos novos em `signatures[]` (`cpf`, `certificateType`, `certificateTypeLabel`)
não têm consumidor para quebrar — nascem prontos para o primeiro que usar.

## Melhorias OPCIONAIS (não obrigatórias)

- **`App.svelte`** — mostrar o tipo no status de validação:
  ```js
  setStatus(`Certificado válido: ${data.commonName} · ${data.certificateTypeLabel}${expiry}`, ...)
  ```
- **Tela de verificação** (quando existir) — exibir badge "ICP-Brasil" vs "gov.br"
  e o CPF quando presente, ajudando o advogado a distinguir a natureza jurídica
  da assinatura (qualificada ICP-Brasil × avançada gov.br).

## Contrato dos campos novos

- `certificateType`: `"ICP_BRASIL"` | `"GOV_BR"` | `"OTHER"` (estável p/ lógica).
- `certificateTypeLabel`: rótulo legível, pode mudar (não usar em `if`).
- `cpf`: string com 11 dígitos ou `null` (gov.br/other não têm CPF no documento).

---

# Impacto — verificação de cadeia ICP-Brasil (v1.1.0, 2026-09-06)

> Mudança: `/certificate/validate` e `/certificate/info` passaram a devolver
> `chain_status`, `chain_reason` e `chain_issuer`. O `/health` passou a devolver
> `version`.

## TL;DR — Nada quebra. ✅ Mas há uma decisão a tomar no Rails.

**Puramente aditivo**: nenhum campo existente foi removido ou renomeado.
`valid` continua significando exatamente o que significava — senha correta e
dentro da validade. A pergunta nova ("a cadeia fecha?") vive em campo próprio.

## Por que isto passou a existir aqui

O `CertificateTypeDetector` classifica ICP-Brasil por marcadores **declarados no
próprio certificado** (OID `2.16.76.1.3.x`, `O=ICP-Brasil` no subject,
"ICP-Brasil" no issuer). Isso é auto-declaração, não prova: um autoassinado
gerado com `-subj "/O=ICP-Brasil/..."` passa por lá. Faltava a verificação
criptográfica, e ela pertence a este serviço — que já é o dono do `.pfx` e roda
numa plataforma com PKIX nativo.

## Os três estados

| `chain_status` | `chain_reason` | Significado |
|---|---|---|
| `verified` | `chain_verified` | Fecha contra o truststore configurado |
| `untrusted` | `self_signed` | Autoassinado — **detectado sem truststore** |
| `untrusted` | `untrusted_root` | Raiz recusada pelo truststore |
| `unverified` | `no_truststore` | Não há truststore para conferir |
| `unverified` | `error` / `no_certificate` | Não foi possível abrir/ler |

**`unverified` nunca deve ser lido como "confiável"** — é ausência de resposta,
não resposta negativa nem positiva.

## Configuração (v1.2.0: já vem ligado)

**As 12 raízes públicas da ICP-Brasil (v2–v13) vão embarcadas no jar**
(`resources/pki/icp-brasil-roots.pem`, baixadas do repositório oficial do ITI).
A verificação funciona sem configurar nada.

`ICP_BRASIL_TRUSTSTORE_PATH` continua existindo e tem **precedência**, para
apontar outro truststore sem rebuild (senha opcional em
`ICP_BRASIL_TRUSTSTORE_PASSWORD`).

Embarcar foi decisão consciente: depender da variável faria a verificação nascer
desligada, e recurso de segurança desligado por omissão é recurso que não existe.
As raízes são públicas — não há segredo indo para a imagem, e o build deixa de
depender de a rede do ITI estar no ar.

**Revogação (OCSP/CRL) fica de fora nesta versão**, de propósito: exigiria rede
no momento do upload e transformaria indisponibilidade do provedor em recusa de
certificado bom.

## Consumidores

### Backend Rails — `ProcStudio-Docker/api`
O PR #372 (PRC-857) implementou a mesma verificação **em Ruby**, no
`A1Signature::VerifyCertificateChainService`, com as colunas
`chain_verified` / `chain_verified_at` / `chain_verification_reason` já criadas
na migration `20260906100000`.

**Decisão pendente:** trocar a verificação local por uma chamada a
`/certificate/validate` e gravar o que vier. O modelo `A1Certificate#chain_status`
e a tela **não precisam mudar** — muda só a origem do dado. Motivo para trocar:
um truststore, um lugar; se ficar nos dois, eles divergem.

### Frontend do assinador
Ignora os campos novos. Impacto: nenhum.

## Verificar o deploy

`/health` agora traz a versão, lida de `/app/VERSION`:

```bash
curl -s https://signer.procstudio.com.br/api/v1/health
# {"status":"ok","service":"document-signer","version":"1.1.0","timestamp":"..."}
```

Antes disso a versão só existia no rodapé do frontend — não dava para conferir
deploy por API nem por automação.

## v1.3.0 — cadeia de certificação no `/verify/pdf` (PRC-1015, 2026-09-11)

Cada item de `signatures[]` (e o alias `signature`) ganha três campos:

| campo | valores | significado |
|---|---|---|
| `chainStatus` | `verified` · `untrusted` · `unverified` | a cadeia fecha contra o truststore ICP-Brasil? (`unverified` = não deu para conferir; nunca vira confiança) |
| `chainReason` | `chain_verified` · `self_signed` · `untrusted_root` · `no_truststore` · `error` | motivo legível por máquina |
| `chainIssuer` | DN | issuer do certificado final, para exibição |

- **Aditivo.** `valid`, `integrityValid`, `certificateValid`, `coversWholeDocument` continuam com o mesmo
  significado; ninguém quebra por ignorar os campos novos.
- **gov.br** (`certificateType: GOV_BR`) sai `untrusted / untrusted_root`: o truststore embarcado só tem as
  raízes ICP-Brasil v2–v13, não há raiz Gov-Br. É esperado e documentado; o consumidor decide.
- **Autoassinado com `O=ICP-Brasil` no subject** agora sai `certificateType: ICP_BRASIL` + `chainStatus:
  untrusted / self_signed`. Antes passava como `valid: true` sem nenhum sinal.
- **A1 real** (fixtures `tests/verify/mix-*.pdf`, AC SyngularID) fecha `verified / chain_verified`.

Consumidor ProcStudio (`api/app/services/customer_forms/signed_pdf_verifier.rb` e
`envelopes/signed_pdf_verifier.rb`), desde 2026-09-12: assinatura `ICP_BRASIL` com `chainStatus:
untrusted` é recusada com **422** (status desconhecido também, fail-closed); `chainStatus: unverified`
(`no_truststore`, `error`, `no_certificate`) é tratado como **falha do Signer, não do cliente** —
responde **503 "tente novamente"** e loga em ERROR, nunca a mensagem que manda reassinar com outro
certificado. `GOV_BR` segue aceito por nome + conteúdo + integridade; campo ausente (Signer anterior a
1.3.0) é tolerado com aviso no log. Do lado de cá, a v1.3.1 loga em ERROR todo caminho que produz
`unverified` por falha nossa, para as duas pontas contarem a mesma história.

---

# Impacto — posição da assinatura visível (v1.4.0, 2026-11-12)

> Mudança: `/sign/pdf`, `/sign/pdf/json` e `/sign/pdf/batch` ganham um contrato
> de coordenadas explícito para `page`/`x`/`y`/`width`/`height`, validação com
> 400 legível e, no lote, posição por arquivo (`placements`). A página pública
> ganha a prévia com a caixa arrastável.

## TL;DR — quem só usa atalho de canto não muda nada. Quem passar a mandar x/y segue o contrato abaixo.

## O contrato, em cinco linhas

1. **Unidade:** pontos PDF (1/72"), decimais aceitos.
2. **Página:** `page` conta a partir de 1; `0` = última.
3. **Espaço:** a página como o leitor a vê — CropBox (limitado ao MediaBox), já girada pelo `/Rotate`.
4. **Origem:** canto inferior esquerdo da página visível, y para cima; `x`/`y` são o canto inferior
   esquerdo da caixa, `width`/`height` o tamanho dela (padrão 240 × 102, mínimo 120 × 51).
5. **Validação:** a caixa inteira tem de caber na página (folga 0,5 pt); senão **400** com
   `code: INVALID_SIGNATURE_PAGE | INVALID_SIGNATURE_PLACEMENT` e o motivo em `error`.

Fonte da verdade: `src/main/java/com/example/documentsigner/pades/SignaturePlacement.java`.
Testes: `SignaturePlacementTest` (confere o `/Rect` do widget **e** os pixels da página renderizada,
inclusive em página girada e com CropBox deslocado) e `SignPdfPlacementApiTest` (porta HTTP).

## O que muda de comportamento (e para quem)

| Antes | Agora | Quem sente |
|---|---|---|
| `x`/`y` só valiam com `position=custom`; com outro `position` eram ignorados em silêncio | `x` e `y` juntos sempre valem e têm prioridade sobre `position` | Quem mandava `x`/`y` junto com um atalho (o Rails não manda) |
| `x`/`y` inteiros (`x=10.5` → 500) | decimais aceitos | — |
| `width`/`height` mudavam só a borda; o texto ficava no tamanho fixo | o carimbo inteiro escala | Quem mandava tamanho diferente de 240 × 102 (o Rails não manda) |
| `width`/`height` qualquer | mínimo 120 × 51 → 400 abaixo disso | idem |
| Caixa fora da página era gravada assim mesmo (carimbo invisível ou cortado) | 400 `INVALID_SIGNATURE_PLACEMENT` | Qualquer consumidor com coordenada errada — passa a ver o erro |
| `position` desconhecido caía em `bottom-right` | 400 `INVALID_SIGNATURE_PLACEMENT` | Quem mandava atalho com erro de digitação |
| Página inexistente: 400 `INVALID_DOCUMENT` | 400 `INVALID_SIGNATURE_PAGE` (mesma mensagem) | Quem comparava o `code` (ninguém mapeado) |
| Página com `/Rotate`: carimbo deitado, canto calculado no MediaBox cru | carimbo em pé, no canto que o leitor vê | Todos — é correção |
| CropBox deslocado ignorado | respeitado | Todos — é correção |
| `x=abc` → 500 | 400 `BAD_REQUEST` | — |

Em página sem rotação e com CropBox = MediaBox na origem (o caso comum), os atalhos de canto e as
coordenadas caem **exatamente** onde caíam.

## Consumidores

### Backend Rails — `ProcStudio-Docker/api` (`A1Signature::SignDocumentService`)
- Hoje manda `visible`, `page` (já resolvido: `last` vira o número da última página) e `position`
  (um dos quatro atalhos). **Nada quebra.**
- Já repassa `x`/`y`/`width`/`height` se o chamador os fornecer. Com a 1.4.0 eles passam a valer sem
  precisar de `position=custom`. O posicionamento manual do ProcStudio (branch
  `brpl/assinaturas-posicionamento-20261112`) usa exatamente este contrato e **depende da 1.4.0 em
  produção**: contra a 1.3.x, `x`/`y` enviados com um atalho são ignorados e a assinatura cai no canto.
- Lê `error`/`message` na falha: as mensagens novas chegam em inglês no campo `error`, com `code`
  estável para traduzir.

### Frontend do assinador
- Passa a mandar `page` + `x`/`y`/`width`/`height` quando o usuário ajusta a caixa na prévia; sem
  ajuste continua mandando `page` + `position`.
- Erros de posição são traduzidos pelo `code`.
- A página aceita um PDF por vez, então não usa `placements`; o parâmetro existe para consumidores de API.

## Verificar o deploy

```bash
curl -s https://signer.procstudio.com.br/api/v1/health   # "version":"1.4.0"
```

O worker do pdf.js é servido como `.mjs`; o `docker/nginx.conf` ganhou um `location` que garante
`Content-Type: application/javascript` para essa extensão (com `nosniff`, o navegador recusa o worker
se vier como `application/octet-stream`). Depois do deploy, abrir a página, escolher um PDF e conferir
que a prévia aparece. Se não aparecer, a assinatura continua funcionando pelos atalhos de canto.

## Limites conhecidos da prévia

- PDF protegido por senha ou corrompido: a prévia não abre e a página avisa; a assinatura segue pelos
  atalhos.
- Imagens JPEG 2000/JBIG2 e perfis ICC dependem dos decodificadores WebAssembly do pdf.js, que **não**
  foram habilitados (exigiriam `'wasm-unsafe-eval'` no CSP). Nesses PDFs a imagem pode não aparecer na
  prévia; o texto e a posição da caixa continuam corretos, e o PDF assinado não é afetado.
