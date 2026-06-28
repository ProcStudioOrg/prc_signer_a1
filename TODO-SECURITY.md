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

### 3. CORS aberto (`origins = "*"`)
- **Onde:** `src/main/java/com/example/documentsigner/api/SignerController.java:31`
  — `@CrossOrigin(origins = "*")`.
- **Risco:** qualquer site pode chamar a API a partir do navegador da vítima. Para
  um endpoint que recebe certificado + senha, isso amplia muito a superfície (ex.:
  página maliciosa que reusa um upload em andamento / phishing de fluxo).
- **Ação:** restringir a origem ao domínio oficial do front (lista branca). Remover
  o wildcard em produção.

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

### 7. Senha como `String` (não `char[]`)
- **Onde:** `CertificateValidator.java` (e toda a cadeia) usa `String password` →
  `password.toCharArray()`. `PdfSigner`/`SigningService` propagam `String`.
- **Risco:** `String` é imutável e fica no heap até o GC (não dá pra zerar). A senha
  do certificado persiste mais tempo na memória; aparece em heap dump.
- **Ação:** quando viável, usar `char[]` ponta a ponta e zerar (`Arrays.fill`) após
  uso. Zerar também os `byte[]` do certificado e da chave após assinar.

### 8. Zeragem de segredos em memória
- **Onde:** controller/serviço não limpam `certBytes` / `pdfBytes` / senha após uso.
- **Risco:** segredos sobrevivem no heap até o GC; risco em heap dump / swap.
- **Ação:** após assinar, sobrescrever buffers sensíveis. Considerar desabilitar
  core dumps do processo Java em produção.

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

## Decisões em aberto (precisam de definição do produto)

- [ ] É 100% sem login (landing pública) ou vai ter área autenticada do ProcStudio?
      Isso muda muito o modelo de ameaça (rate limit vs. auth real).
- [ ] O app fica atrás de proxy reverso com TLS? Qual? (confirmar buffering em disco)
- [ ] Existe requisito legal/LGPD de não reter o documento nem metadados do titular?
- [ ] Aceitável o envio do documento ao ITI, ou só validação local?
