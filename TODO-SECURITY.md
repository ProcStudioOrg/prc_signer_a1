# TODO — Segurança (arquivos, certificados e senhas)

> Levantamento inicial. Nada aqui foi aplicado ainda — é uma lista de revisão.
> Contexto: o app recebe **certificado A1 (.pfx/.p12)**, **senha do certificado** e
> **PDFs** do usuário, assina e devolve. Os dados mais sensíveis em jogo são a
> **chave privada do advogado** e a **senha dela**. Tratar como segredo de alto valor.

## O que já está bom (não regredir)

- **Nenhuma persistência.** A API processa tudo em memória (`byte[]`). Certificado,
  senha, PDF original e PDF assinado não são gravados em banco, S3 nem disco de
  propósito. O PDF assinado / `.p7s` volta direto na resposta HTTP.
  - Os únicos `FileOutputStream`/`Files.write` estão no app desktop Swing legado
    (`DocumentSignerUI.java`) e no `signPdf()` por caminho de arquivo — **não** são
    usados pela API web.
- **Frontend** não guarda nada sensível: só `ps-theme` no `localStorage`
  (`frontend/src/App.svelte`). Senha fica só em variável de memória da página.

---

## Prioridade ALTA

### 1. Uploads encostam no disco (multipart temp)
- **Onde:** `src/main/resources/application.properties` — define `max-file-size=50MB`
  mas **não** define `file-size-threshold`. Padrão do Spring Boot é `0B`, então o
  Tomcat grava cada upload (o `.pfx` e o PDF) em arquivo temporário no disco
  (`java.io.tmpdir`) durante a requisição.
- **Risco:** chave privada e PDF tocam o disco transitoriamente; sobram em swap,
  snapshot de VM, crash dump ou se o processo morrer antes de apagar o temp.
- **Ação:** manter uploads em memória:
  ```properties
  spring.servlet.multipart.file-size-threshold=60MB
  ```
  Avaliar também apontar `spring.servlet.multipart.location` para um `tmpfs`
  (RAM-backed) como segunda camada.

### 2. Sem TLS / HTTPS configurado
- **Onde:** `application.properties` — `server.port=8080`, sem `server.ssl.*`.
- **Risco:** senha do certificado + chave privada trafegam em claro. Se não houver
  TLS no proxy reverso (nginx/supervisord no Docker), é interceptação trivial.
- **Ação:** garantir HTTPS obrigatório (TLS no proxy ou no Spring). Redirecionar
  HTTP→HTTPS, habilitar HSTS. Confirmar que o proxy **não faz buffer em disco** do
  corpo do upload (`proxy_request_buffering off` no nginx, ou buffer em memória).

### 3. CORS aberto (`origins = "*"`) — ✅ RESOLVIDO (2026-07-03)
- **Onde:** `SignerController` — era `@CrossOrigin(origins = "*")`.
- **Correção aplicada:** whitelist com as origens oficiais ProcStudio:
  `https://signer.procstudio.com.br`, `https://hml.procstudio.com.br`,
  `https://procstudio.com.br`. Wildcard removido.

### 4. Sem autenticação e sem rate limiting
- **Onde:** nenhuma config de Spring Security / `@PreAuthorize` / rate limiter no
  projeto.
- **Risco:** endpoint público de operação cara (assinatura/validação). Permite
  brute-force de senha de certificado, abuso/DoS e uso anônimo ilimitado.
- **Ação:** decidir o modelo (é landing page pública de utilidade, então talvez sem
  login). No mínimo: rate limiting por IP, limite de tamanho/throughput, CAPTCHA ou
  proof-of-work no endpoint de assinatura, e timeout agressivo. Limitar tentativas
  de senha por certificado/IP.

---

## Prioridade MÉDIA

### 5. Logging em DEBUG pode vazar dado sensível
- **Onde:** `application.properties` — `logging.level.com.example.documentsigner=DEBUG`.
- **Risco:** DEBUG em produção tende a registrar nomes de arquivo, metadados do
  certificado (titular, CPF/CNPJ no CN), e potencialmente exceções com conteúdo.
  Logs viram cópia persistente de dado sensível.
- **Ação:** `INFO`/`WARN` em produção. Auditar que **nunca** se loga senha, bytes do
  certificado ou bytes do PDF. Adicionar checagem no PR.

### 6. Mensagens de erro internas vazam para o cliente
- **Onde:** `src/main/java/com/example/documentsigner/api/GlobalExceptionHandler.java`
  — retorna `e.getMessage()` e `"Internal server error: " + e.getMessage()`.
- **Risco:** detalhes de stack/infra/biblioteca expostos ao cliente (information
  disclosure). Em erros de cripto, a mensagem pode revelar detalhes do keystore.
- **Ação:** mensagens genéricas + código de correlação para o cliente; detalhe só no
  log do servidor. Não concatenar `getMessage()` na resposta.

### 7. Senha como `String` (não `char[]`) — ✅ PARCIALMENTE RESOLVIDO (2026-07-04)
- **Feito:** todo ponto de `keystore.load`/`getKey` agora usa um `char[] pw` único,
  zerado (`Sensitive.wipe`) logo após extrair a chave (DocumentSigner, PdfSigner,
  PadesSignerService, CertificateValidator).
- **Limite honesto (não fechável em puro JCA):** a senha **chega** da camada HTTP
  como `String` imutável (`@RequestParam`) — essa cópia não dá pra zerar, persiste
  até o GC. E o `PrivateKey` decifrado não é destruível de forma confiável (RSA
  lança em `destroy()`). Então é redução de janela, não eliminação. Ver `Sensitive.java`.

### 8. Zeragem de segredos em memória — ✅ RESOLVIDO (2026-07-04)
- **Feito:** todos os 9 endpoints que recebem certificado zeram o `certBytes` (PKCS12
  com a chave privada criptografada) num `finally` após o uso — inclusive o batch
  (zera após o loop). Helper `util/Sensitive.wipe(byte[]/char[])`.
- **Nota:** `pdfBytes` não é zerado de propósito (PDF não é segredo e volta ao
  cliente). Core dumps do processo Java em produção: ainda a avaliar (infra).

---

## Prioridade BAIXA / Higiene

### 9. Tamanho/validação de upload e tipo de arquivo
- **Risco:** PDFs maliciosos (PDF bomb, conteúdo malformado) podem causar consumo
  excessivo de CPU/memória no parser (PDFBox).
- **Ação:** validar magic bytes (`%PDF`), limites de tamanho coerentes, timeout de
  parsing, e isolar recursos (limites de heap por requisição se possível).

### 10. Cabeçalhos de segurança HTTP
- **Ação:** adicionar `Content-Security-Policy`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy`, `Cache-Control: no-store` nas respostas que carregam o PDF
  assinado (evitar cache em proxy/navegador do documento assinado).

### 11. Dependências e CVEs
- **Ação:** rodar `dependency-check`/`mvn versions` periodicamente. BouncyCastle,
  PDFBox e libs de assinatura têm histórico de CVEs; manter atualizadas.

### 12. Validação externa via ITI (verificador.iti.gov.br)
- **Onde:** `ItiVerificador` / endpoints `/verify/iti`, `/sign/verified`.
- **Risco:** envia documento + assinatura para serviço externo do governo. Confirmar
  que isso é intencional e comunicado ao usuário (sai da máquina do app).
- **Ação:** documentar/consentir o envio externo; garantir TLS e timeouts; não usar
  por padrão sem o usuário saber.

### 13. Retenção de logs e telemetria
- **Ação:** definir retenção curta de logs de acesso; garantir que logs de proxy não
  guardem corpo de requisição (upload). Sem APM que capture payloads.

### 14. Temp files do batch / ZIP em memória
- **Onde:** `signPdfPadesBatch` monta o ZIP em `ByteArrayOutputStream` (memória) —
  ok. Confirmar que nenhum caminho de batch escreve PDFs assinados em disco.

---

---

## Achados do teste online em produção (2026-07-03)

> Testes feitos contra `https://signer.procstudio.com.br/api/v1/` com certificado
> A1 real e PDFs assinados por A1 e gov.br. Complementam a lista acima.

### Confirmado em produção (já listado)
- **CORS wildcard (#3) confirmado ao vivo:** resposta de produção traz
  `access-control-allow-origin: *` e `access-control-allow-methods: POST` no
  preflight. Segue valendo restringir ao domínio do front.
- **Headers de segurança (#10) parcialmente presentes:** produção já envia
  `X-Frame-Options: SAMEORIGIN`, `X-Content-Type-Options: nosniff`,
  `X-XSS-Protection`. Falta `Content-Security-Policy`, `Referrer-Policy` e
  `Cache-Control: no-store` nas respostas com PDF assinado.

### 15. Erros de cliente retornam HTTP 500 (higiene de status + info disclosure)
- **Onde:** `GlobalExceptionHandler` / fluxo dos controllers. Observado em prod:
  - request sem multipart → `500 INTERNAL_ERROR` ("Current request is not a
    multipart request") — deveria ser **400**.
  - PDF corrompido/inválido → `500 SIGNING_ERROR` — deveria ser **400**.
  - `GET` em rota `POST` → **500** — deveria ser **405**.
  - assinatura destacada que **não bate** com o documento (adulteração!) →
    `500 SIGNING_ERROR` "message-digest ... does not match" — deveria ser
    **200 `valid:false`** (ou 400), não erro de servidor.
- **Risco (segurança):** (a) resultado legítimo de "documento adulterado" é
  mascarado como falha de servidor, dificultando detecção de fraude pelo
  cliente; (b) 5xx com `getMessage()` cru vaza detalhe interno (liga com #6);
  (c) tudo virando 500 afoga o monitoramento — um 5xx real (ataque/instabilidade)
  se perde no ruído de erros que são culpa do cliente.
- **Ação:** mapear exceções para status corretos (400/405), e tratar mismatch de
  assinatura como **resultado de verificação** (`valid:false`, HTTP 200), não
  exceção. Mensagens genéricas + código de correlação (ver #6).

### 16. Endpoint de validação ITI dá falso "sucesso" — ✅ RESOLVIDO (2026-07-03)
- **Era:** `/verify/iti` respondia 502 em produção; `staging=true` retornava
  `success:true` com o HTML da homepage do ITI — falso-positivo perigoso.
- **Correção aplicada:** endpoints `/verify/iti` e `/sign/verified` **removidos**
  e a classe `ItiVerificador` deletada (não há API pública no ITI). Verificação
  confiável passa a ser exclusivamente **local** (BouncyCastle): `/verify` e
  `/verify/pdf`. `/sign/pdf/verified` mantido (verificação local). Liga com #12.

---

## Decisões em aberto (precisam de definição do produto)

- [ ] É 100% sem login (landing pública) ou vai ter área autenticada do ProcStudio?
      Isso muda muito o modelo de ameaça (rate limit vs. auth real).
- [ ] O app fica atrás de proxy reverso com TLS? Qual? (confirmar buffering em disco)
- [ ] Existe requisito legal/LGPD de não reter o documento nem metadados do titular?
- [ ] Aceitável o envio do documento ao ITI, ou só validação local?
