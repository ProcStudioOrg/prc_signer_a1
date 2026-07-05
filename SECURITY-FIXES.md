# Checklist de Correções de Segurança

> Consolida os achados do teste online (2026-07-03) + itens do `TODO-SECURITY.md`.
> Marca o que é **fix seguro** (aplicável sem decisão de produto) vs **precisa de
> decisão**. Fonte da verdade do TODO permanece `TODO-SECURITY.md`.

Legenda: `[ ]` pendente · `[~]` em andamento · `[x]` feito

## Fix seguro (backend) — sem dependência de decisão

- [x] **#15a** `/verify` (CAdES) mismatch → `200 {valid:false}` em vez de 500
      (`DocumentSigner.verifySignature`: captura `CMSException` → retorna false).
- [x] **#15b** Status HTTP corretos p/ erros de cliente (`GlobalExceptionHandler`):
      multipart/parte ausente → **400**, método errado → **405**, media type → **415**,
      param faltando → **400**.
- [x] **#15c / #9** PDF malformado no `/verify/pdf` → **400** (valida magic bytes
      `%PDF` + mapeia `IOException` de parse → `InvalidDocumentException`).
- [x] **#6** Handler genérico e `SigningException` não vazam `e.getMessage()`:
      mensagem genérica + `correlationId` (UUID) na resposta; detalhe só no log.
- [x] **#1** Uploads em memória: `spring.servlet.multipart.file-size-threshold=100MB`
      (não encosta `.pfx`/PDF no disco).
- [x] **#5** Logging de produção `INFO` (era `DEBUG`).

## Fix seguro (nginx / frontend)

- [x] **#10** Headers no nginx: `Content-Security-Policy`, `Referrer-Policy`,
      `Strict-Transport-Security`, `Cache-Control: no-store` + `proxy_request_buffering off`
      no `/api/` (PDF assinado não cacheia nem bufferiza em disco).
- [x] **frontend** Higiene do campo de senha (`autocomplete/autocapitalize/autocorrect=off`,
      `spellcheck=false`). *Limpeza da senha da memória: deixada de fora p/ não quebrar
      assinatura em lote — reavaliar.*

## Decidido

- [x] **#3 CORS** — travado nas origens oficiais: `signer.procstudio.com.br`,
      `hml.procstudio.com.br`, `procstudio.com.br` (`SignerController` @CrossOrigin).
- [x] **#16 ITI** — **removido**. Endpoints `/verify/iti` e `/sign/verified`
      apagados + classe `ItiVerificador` deletada. Verificação confiável continua
      LOCAL: `/verify` (CAdES) e `/verify/pdf` (PAdES). `/sign/pdf/verified` mantido
      (verifica localmente; método renomeado `signPadesAndVerify`).
- [~] **#4 Rate limiting / auth** — **adiado** por decisão do produto. Segue público
      sem limite por ora; reavaliar (nginx `limit_req` é o caminho de menor atrito).

## Maior esforço (agendar)

- [x] **#7/#8** Higiene de segredos em memória: senha `char[]` zerada nos pontos de
      carga + `certBytes` (PKCS12) zerado no `finally` de todos os 9 endpoints.
      Helper `util/Sensitive`. Limite: o `String` de entrada e o `PrivateKey`
      decifrado não são scrubáveis em JCA (documentado).
- [ ] **#11** Varredura de CVEs (BouncyCastle 1.70, PDFBox 2.0.27 — avaliar bump).
- [ ] **#2 (TLS)** HSTS já adicionado no nginx; confirmar redirect HTTP→HTTPS na borda.

## Em aberto (próximo)

- [ ] **#1 Validação de cadeia** — CertPath PKIX + truststore ICP-Brasil **+ gov.br**
      (decidido). Dá peso jurídico real; destrava revogação. Trust anchors: buscar
      do ITI/gov.br (offline, uma vez). Revogação segue best-effort/soft-fail.
- [~] **#4 Rate limiting** — adiado por decisão de produto.
