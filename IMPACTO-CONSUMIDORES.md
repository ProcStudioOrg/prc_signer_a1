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
`envelopes/signed_pdf_verifier.rb`): assinatura `ICP_BRASIL` com `chainStatus` presente e diferente de
`verified` é recusada; `GOV_BR` segue aceito por nome + conteúdo + integridade; campo ausente (Signer
anterior a 1.3.0) é tolerado com aviso no log.
