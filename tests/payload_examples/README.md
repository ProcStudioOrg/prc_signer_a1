# Exemplos de Payload — prc_signer_a1

Respostas reais capturadas em produção/local (2026-07-03) para referência de
contrato de API, testes e onboarding. Base para o front e para consumidores.

## Arquivos

| Arquivo | Endpoint | Cenário |
|---|---|---|
| `health_response.json` | `GET /health` | health check |
| `certificate-info_response_icp-brasil.json` | `POST /certificate/info` | cert A1 ICP-Brasil (campos de tipo) |
| `certificate-info_response_full.json` | `POST /certificate/info` | resposta completa (validade, algoritmo) |
| `verify-pdf_response_icp-brasil-a1.json` | `POST /verify/pdf` | PDF assinado A1 → `ICP_BRASIL` + cpf |
| `verify-pdf_response_govbr.json` | `POST /verify/pdf` | PDF assinado gov.br → `GOV_BR`, cpf null |
| `errors_current_behavior.md` | vários | casos de erro (baseline pré-fix #15) |

## Detecção de tipo de certificado (campos novos)

`certificateType` ∈ `ICP_BRASIL` | `GOV_BR` | `OTHER`. Ver `../../CERTIFICATE-TYPES.md`.

- **ICP-Brasil (A1/A3):** `cpf` preenchido, `certificateType=ICP_BRASIL`.
- **gov.br:** `cpf=null`, `certificateType=GOV_BR`, `revocation.state=NOT_CHECKED`
  (cadeia curta — só a folha vem embutida).

## Como regenerar

```sh
B=https://signer.procstudio.com.br/api/v1        # ou http://localhost:8081/api/v1 (--api)
PASS='<senha em tests/PASS.md>'
# verify GOV.BR
curl -s -F "document=@<pdf_govbr>.pdf" $B/verify/pdf | python3 -m json.tool
# verify A1 (assina e verifica)
curl -s -o s.pdf -F "document=@../simple.pdf" -F "certificate=@../BP.pfx" -F "password=$PASS" $B/sign/pdf
curl -s -F "document=@s.pdf" $B/verify/pdf | python3 -m json.tool
# certificate info
curl -s -F "certificate=@../BP.pfx" -F "password=$PASS" $B/certificate/info | python3 -m json.tool
```
