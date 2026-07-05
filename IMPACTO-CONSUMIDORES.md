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
