# ProcStudio Document Signer

Assinador de documentos PDF com certificados A1 (padrão brasileiro). Suporta **três modos de execução**:
- 🖥️ **GUI Desktop** (Java Swing)
- 🌐 **Interface Web** (Svelte)
- 🔌 **API REST** (Spring Boot)

## 🚀 Execução Rápida

### Docker (API + Web Interface)

O container roda **Backend API + Frontend Web** simultaneamente:

```bash
# Build
docker build -t procstudio-signer .

# Run
docker run -p 80:80 procstudio-signer
```

Acesse:
- **Interface Web**: http://localhost
- **API REST**: http://localhost/api/v1/health

### Local - GUI Desktop

```bash
# Compilar
mvn clean package

# Executar interface desktop
java -jar target/ProcStudioSigner2.jar
```

### Local - API + Frontend (Desenvolvimento)

**Backend:**
```bash
java -jar target/ProcStudioSigner2.jar --api
```

**Frontend:**
```bash
cd frontend
npm install
npm run dev
```

## 📦 Downloads

Você pode baixar a versão mais recente em nossa [página de releases](https://github.com/brpl20/document-signer/releases).

## 📋 Requisitos

- **Java**: JRE 11 ou superior
- **Docker**: (opcional, para container)
- **Node.js 18+**: (opcional, para desenvolvimento frontend)

## 🏗️ Arquitetura do Container

```
Container (Port 80)
├── Nginx (Frontend)
│   ├── Svelte SPA
│   └── Proxy /api/* → Backend
└── Spring Boot API (Backend)
    ├── Assinatura digital
    └── Port 8080 (interno)
```

Gerenciado por **Supervisor** para alta disponibilidade.

📚 Veja [Arquitetura Detalhada](docs/ARQUITETURA_CONTAINER.md)

---

## 🌐 Uso - Interface Web

Acesse http://localhost após rodar o container.

Funcionalidades:
- ✅ Validar certificado digital
- ✅ Assinar PDF com assinatura visual (PAdES)
- ✅ Assinar múltiplos documentos
- ✅ Assinatura destacada (.p7s)
- ✅ Download automático de arquivos assinados

---

## 🖥️ Uso - Modo GUI Desktop

1. Abra o aplicativo: `java -jar target/ProcStudioSigner2.jar`
2. Selecione seu certificado digital A1 (.pfx)
3. Digite a senha do certificado
4. Escolha uma opção:
   - **Assinar arquivo**: Assina um único PDF
   - **Assinar múltiplos**: Seleciona vários PDFs
   - **Assinar pasta**: Assina todos PDFs de uma pasta
   - **Arrastar e soltar**: Arraste PDFs diretamente para a janela

Os arquivos assinados são salvos com extensão `.p7s`.

### Recursos da Interface Desktop

- Lembra o último diretório usado
- Lembra o último certificado selecionado
- Suporte a arrastar e soltar (drag-and-drop)

---

## 🔌 Uso - API REST

### Endpoints Disponíveis

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| `GET` | `/api/v1/health` | Health check |
| `POST` | `/api/v1/certificate/info` | Detalhes do certificado |
| `POST` | `/api/v1/certificate/validate` | Validar senha e validade |
| `POST` | `/api/v1/sign` | Assina documento (P7S) |
| `POST` | `/api/v1/sign/json` | Assina (retorna JSON base64) |
| `POST` | `/api/v1/sign/batch` | Assina múltiplos (P7S) |
| `POST` | `/api/v1/sign/pdf` | Assina PDF com visual (PAdES) |
| `POST` | `/api/v1/sign/pdf/batch` | Assina múltiplos PDFs (PAdES) |
| `POST` | `/api/v1/sign/verified` | Assina e valida no ITI |
| `POST` | `/api/v1/verify` | Verifica assinatura localmente |
| `POST` | `/api/v1/verify/iti` | Verifica no ITI Verificador |

### Exemplos com cURL

#### Health Check

```bash
curl http://localhost:8080/api/v1/health
```

#### Assinar PDF com Assinatura Visual (PAdES)

```bash
curl -X POST http://localhost:8080/api/v1/sign/pdf \
  -F "document=@documento.pdf" \
  -F "certificate=@certificado.pfx" \
  -F "password=sua_senha" \
  -F "visible=true" \
  -F "page=1" \
  -F "position=bottom-right" \
  -o documento_assinado.pdf
```

##### Posição da assinatura visível (contrato de coordenadas, v1.4.0)

Vale para `/sign/pdf`, `/sign/pdf/json` e `/sign/pdf/batch`. A conta mora em
`pades/SignaturePlacement.java`; o frontend usa a mesma em `frontend/src/lib/placement.js`.

| Parâmetro | Padrão | Significado |
|---|---|---|
| `visible` | `false` | `true` desenha o carimbo; sem isso os demais são ignorados |
| `page` | `0` | Página, **contando a partir de 1**. `0` = última página |
| `position` | `bottom-right` | Atalho de canto: `bottom-right`, `bottom-left`, `top-right`, `top-left` (margem de 20 pt). Usado quando `x`/`y` não vêm |
| `x`, `y` | — | Canto **inferior esquerdo** da caixa. Enviados juntos, têm prioridade sobre `position` |
| `width`, `height` | `240`, `102` | Tamanho da caixa. Mínimo 120 × 51. O carimbo escala por um fator único (o menor entre largura/240 e altura/102), ancorado no topo esquerdo |

- **Unidade:** pontos PDF (1/72 de polegada); decimais são aceitos.
- **Espaço:** a página **como o leitor a vê** — recortada pelo CropBox (limitado ao MediaBox) e já girada
  pelo `/Rotate`. Uma página A4 retrato com `/Rotate 90` mede 842 × 595 para este contrato, e o carimbo sai em pé.
- **Origem:** canto inferior esquerdo da página visível; `x` cresce para a direita e `y` para cima.
  Quem trabalha em tela (origem no topo) converte com `y = alturaDaPágina − topo − altura`.
- **Validação:** a caixa inteira precisa caber na página (folga de 0,5 pt). Fora disso a resposta é
  **400** com `code` e o motivo em `error`:

| `code` | Quando |
|---|---|
| `INVALID_SIGNATURE_PAGE` | `page` maior que o número de páginas |
| `INVALID_SIGNATURE_PLACEMENT` | caixa fora da página, menor que o mínimo, `x` sem `y`, `position=custom` sem coordenadas, atalho desconhecido, `placements` malformado |
| `BAD_REQUEST` | valor que não é número (`x=abc`, `page=1.5`) |

```bash
# Carimbo de 240 × 102 pt com o canto inferior esquerdo a 300 pt da esquerda
# e 120 pt de baixo, na página 2
curl -X POST http://localhost:8080/api/v1/sign/pdf \
  -F "document=@documento.pdf" -F "certificate=@certificado.pfx" -F "password=sua_senha" \
  -F "visible=true" -F "page=2" -F "x=300" -F "y=120" \
  -o documento_assinado.pdf
```

**Lote (`/sign/pdf/batch`).** `page`/`position`/`x`/`y`/`width`/`height` da requisição valem para todos
os arquivos, cada um validado contra a própria página. Para posição **por arquivo**, envie `placements`:
um array JSON com um item por arquivo, na ordem dos `documents`. Item `null` usa a posição da requisição;
objeto aceita `page`, `x`, `y`, `width`, `height`, `position` (campo ausente herda o da requisição).
`placements` malformado ou com tamanho diferente recusa o lote inteiro (400); posição que não cabe num
arquivo falha só aquele arquivo (`<nome>.error.txt` no ZIP, `X-Failed-Count`).

```bash
curl -X POST http://localhost:8080/api/v1/sign/pdf/batch \
  -F "documents=@a.pdf" -F "documents=@b.pdf" \
  -F "certificate=@certificado.pfx" -F "password=sua_senha" -F "visible=true" \
  -F 'placements=[{"page":1,"x":40,"y":60},null]' \
  -o documentos_assinados.zip
```

#### Assinar Documento (Download .p7s)

```bash
curl -X POST http://localhost:8080/api/v1/sign \
  -F "document=@documento.pdf" \
  -F "certificate=@certificado.pfx" \
  -F "password=sua_senha" \
  -o documento.pdf.p7s
```

#### Assinar Múltiplos PDFs (PAdES)

```bash
curl -X POST http://localhost:8080/api/v1/sign/pdf/batch \
  -F "documents=@doc1.pdf" \
  -F "documents=@doc2.pdf" \
  -F "certificate=@certificado.pfx" \
  -F "password=sua_senha" \
  -F "visible=true" \
  -o documentos_assinados.zip
```

#### Obter Informações do Certificado

```bash
curl -X POST http://localhost:8080/api/v1/certificate/info \
  -F "certificate=@certificado.pfx" \
  -F "password=sua_senha"
```

Resposta:
```json
{
  "valid": true,
  "commonName": "NOME DO TITULAR",
  "issuer": "CN=AC Certificadora",
  "notAfter": "2025-01-01T00:00:00.000+00:00",
  "expired": false,
  "daysUntilExpiry": 365
}
```

📚 [Documentação completa da API](docs/COMO_USAR.md)

---

## 🏛️ ITI Verificador (Validação Oficial)

O sistema integra com o **ITI Verificador**, serviço oficial do Governo Federal para validação de assinaturas digitais ICP-Brasil.

### URLs Oficiais
- **Produção**: https://verificador.iti.gov.br
- **Homologação**: https://verificador.staging.iti.br
- **Portal**: https://validar.iti.gov.br
- **Documentação**: https://validar.iti.gov.br/guia-desenvolvedor.html

### Uso na API

```bash
# Verificar assinatura existente no ITI
curl -X POST http://localhost:8080/api/v1/verify/iti \
  -F "document=@documento.pdf" \
  -F "signature=@documento.pdf.p7s" \
  -F "staging=false"

# Assinar e verificar em uma única chamada
curl -X POST http://localhost:8080/api/v1/sign/verified \
  -F "document=@documento.pdf" \
  -F "certificate=@certificado.pfx" \
  -F "password=sua_senha"
```

---

## 🐳 Deploy em Produção (AWS)

Deploy automático via **CodeBuild → ECR → ECS/Fargate**:

1. Push para o repositório → Dispara CodeBuild
2. CodeBuild builda imagem Docker ARM64
3. Push para Amazon ECR
4. Deploy automático no ECS/Fargate

### Arquitetura AWS Recomendada

```
Internet
    ↓
  ALB (Port 80/443)
    ↓
ECS Fargate (ARM64)
  ├── Container 1: Nginx + API
  ├── Container 2: Nginx + API
  └── Container N: Nginx + API
    ↓
  CloudWatch Logs
```

📚 Veja [Setup CodeBuild ARM64](docs/CODEBUILD_ARM64_SETUP.md)

---

## 🔧 Desenvolvimento

### Build

```bash
mvn clean package
```

### Testes

```bash
# Testes unitários
mvn test

# Teste de assinatura (requer senha do certificado)
CERT_PASSWORD=sua_senha mvn test
```

### Estrutura do Projeto

```
.
├── src/main/java/          # Backend Java
│   └── com/example/documentsigner/
│       ├── Main.java
│       ├── DocumentSigner.java
│       ├── PdfSigner.java
│       ├── api/            # Spring Boot REST API
│       └── exception/
├── frontend/               # Frontend Svelte
│   ├── src/
│   ├── package.json
│   └── vite.config.js
├── Dockerfile              # Multi-stage: Backend + Frontend
├── buildspec.yml           # AWS CodeBuild
└── docs/                   # Documentação
```

---

## 📚 Documentação

- [🏗️ Arquitetura do Container](docs/ARQUITETURA_CONTAINER.md)
- [📖 Como Usar (completo)](docs/COMO_USAR.md)
- [☁️ Setup CodeBuild ARM64](docs/CODEBUILD_ARM64_SETUP.md)
- [📄 Plano PAdES](docs/PADES_IMPLEMENTATION_PLAN.md)

---

## 🔧 Tecnologias

- **Backend**: Java 11, Spring Boot, BouncyCastle, PDFBox
- **Frontend**: Svelte 5, Vite
- **Infra**: Docker, Nginx, Supervisor
- **Cloud**: AWS ECS/Fargate, ECR, CodeBuild, ALB

---

## Bruno Collection

Uma coleção Bruno para testar a API está disponível em `/collection`.

1. Abra o Bruno
2. Clique em "Open Collection"
3. Selecione a pasta `/collection`
4. Execute os requests

---

## 📝 Changelog

### v2.0.0 (Container com Frontend Web)
- ✅ Interface web com Svelte
- ✅ Container com Nginx + API + Supervisor
- ✅ Suporte ARM64 (AWS Graviton)
- ✅ Deploy automatizado AWS CodeBuild/ECS
- ✅ Assinatura visual em PDF (PAdES)
- ✅ Healthcheck e logs centralizados

### v1.4.0
- **Posicionar a assinatura visível.** Na página pública, depois de escolher o
  PDF aparece a prévia da página com a caixa da assinatura: arrastar para
  posicionar, puxar o canto para mudar o tamanho, navegar entre páginas e
  atalhos de canto. Funciona com toque (390 px) e nos dois temas. A prévia usa
  pdf.js (`pdfjs-dist` 5.7.284, Apache-2.0), baixado só quando a prévia abre.
- **Contrato de coordenadas** de `x`/`y`/`width`/`height` documentado e testado
  (ver "Posição da assinatura visível"): pontos, página visível (CropBox +
  `/Rotate`), origem embaixo à esquerda. Idêntico ao comportamento anterior em
  página sem rotação e sem CropBox deslocado.
- `x`/`y` passam a valer sempre que vêm juntos (antes só com
  `position=custom`) e aceitam decimais. `width`/`height` escalam o carimbo
  (antes só mudavam a borda).
- Página girada (`/Rotate`) deixa de sair com o carimbo deitado e no canto
  errado; CropBox deslocado passa a ser respeitado.
- Posição inválida responde **400** com motivo (`INVALID_SIGNATURE_PAGE`,
  `INVALID_SIGNATURE_PLACEMENT`): caixa fora da página, abaixo de 120 × 51 pt,
  `x` sem `y`, atalho desconhecido (antes caía calado em `bottom-right`), número
  inválido (antes 500).
- `/sign/pdf/batch` aceita `x`/`y` e `placements` (posição por arquivo).

### v1.3.1
- `unverified` nunca mais sai em silêncio. `CertificateChainVerifier` loga em
  **ERROR** cada caminho que devolve `chainStatus: unverified` por falha nossa:
  sem âncoras (`no_truststore`), bundle embarcado ausente ou vazio, truststore
  configurado em `ICP_BRASIL_TRUSTSTORE_PATH` ilegível ou sem certificados,
  exceção no PKIX (`error`). PKCS12 que não abre (senha errada) fica em WARN.
  Sem mudança de contrato. Motivo: o consumidor (ProcStudio) passa a responder
  **503 "tente novamente"** para `unverified` em vez de 422 culpando o
  certificado do cliente — e o operador precisa ver a causa no log do Signer.

### v1.3.0
- `POST /verify/pdf` devolve o eixo de CONFIANÇA por assinatura: `chainStatus`
  (`verified` | `untrusted` | `unverified`), `chainReason` (`chain_verified`,
  `self_signed`, `untrusted_root`, `no_truststore`, `error`) e `chainIssuer`.
  Mesma verificação PKIX do `/certificate/validate`, agora sobre a cadeia
  embarcada no CMS. `valid`/`certificateValid` NÃO mudam de significado.
  gov.br não tem raiz embarcada e sai `untrusted/untrusted_root` — o consumidor
  decide (o ProcStudio aceita gov.br por nome + conteúdo e exige `verified`
  para ICP-Brasil). PRC-1015.

### v1.2.0
- Validação de certificado antes de assinar
- Integração com ITI Verificador
- Novos endpoints de validação

### v1.1.0
- Modo API REST
- Suporte drag-and-drop GUI

### v1.0.0
- Versão inicial com GUI desktop

---

## 📞 Suporte

Se encontrar problemas, abra uma [issue no GitHub](https://github.com/brpl20/document-signer/issues).

## 📄 Licença

[Especificar licença]
