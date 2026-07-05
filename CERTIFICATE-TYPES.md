# Detecção de Tipo de Certificado (verificador)

> Como o verificador classifica a origem de uma assinatura e o que cada campo
> do payload significa. Vale para `/verify/pdf` e `/certificate/info`.

## Tipos detectados

| `certificateType` | `certificateTypeLabel` | Como é detectado | CPF disponível? |
|---|---|---|---|
| `ICP_BRASIL` | ICP-Brasil (A1/A3) | OID `2.16.76.1.3.x` no cert, **ou** `O=ICP-Brasil` no subject, **ou** "ICP-Brasil" no issuer | ✅ Sim (no CN `NOME:CPF` ou na OID) |
| `GOV_BR` | gov.br | issuer `O=Gov-Br` / "Governo Federal do Brasil" | ❌ Não (só o nome) |
| `OTHER` | Outro/Desconhecido | nenhum marcador acima (autoassinado, corporativo, estrangeiro) | Depende |

Implementação: `pades/CertificateTypeDetector.java` + enum `pades/dto/CertificateType.java`.

## Por que NÃO distinguimos A1 de A3

**Não dá para saber se foi A1 ou A3 olhando um documento assinado.** A única
diferença entre eles é **onde a chave privada mora**:

- **A1** → chave num arquivo `.pfx`/`.p12` (software)
- **A3** → chave num token USB / smartcard (hardware)

O certificado embutido no PDF e a assinatura resultante são **idênticos** nos
dois casos. Distinguir exigiria ler os OIDs de *Certificate Policy* e cruzar com
as tabelas da DPC da ICP-Brasil — frágil e varia por AC. Fora de escopo.

> Observação: quando **o próprio assinador** assina (upload de `.pfx`), é sempre
> A1 por definição (A3 não sai do token como arquivo). A ambiguidade só existe na
> **verificação** de documentos assinados por terceiros.

## Base empírica (dados reais usados no design)

**e-CPF A1 ICP-Brasil** (`tests/BP.pfx`):
```
subject: CN=BRUNO PELLIZZETTI:05880253996, OU=..., O=ICP-Brasil, C=BR
+ extensão OID 2.16.76.1.3.1 (dados do titular PF)
→ ICP_BRASIL, cpf=05880253996
```

**Assinatura gov.br** (docs assinados no portal gov.br):
```
subject: CN=LAILA KAROLINE FERREIRA PELLIZZETTI          (só nome)
issuer:  C=BR, O=Gov-Br, OU=AC Intermediaria do Governo Federal do Brasil v1,
         CN=AC Final do Governo Federal do Brasil v1
→ GOV_BR, cpf=null
```

## Campos novos no payload

### `POST /verify/pdf` — cada item de `signatures[]` ganhou:
```jsonc
{
  "signerName": "BRUNO PELLIZZETTI",
  "cpf": "05880253996",              // NOVO — null quando gov.br/other
  "certificateType": "ICP_BRASIL",   // NOVO — ICP_BRASIL | GOV_BR | OTHER
  "certificateTypeLabel": "ICP-Brasil (A1/A3)", // NOVO — rótulo p/ UI
  // ...campos existentes: valid, integrityValid, certificateValid, ...
}
```
O alias legado `signature` (singular) também carrega os campos novos.

### `POST /certificate/info` ganhou:
```jsonc
{
  "commonName": "BRUNO PELLIZZETTI",
  "certificateType": "ICP_BRASIL",              // NOVO
  "certificateTypeLabel": "ICP-Brasil (A1/A3)", // NOVO
  // ...campos existentes
}
```

## Compatibilidade

Mudança **aditiva** — nenhum campo existente foi removido ou renomeado.
Consumidores antigos continuam funcionando; novos campos são ignorados por quem
não os lê. Ver `IMPACTO-CONSUMIDORES.md` para quem se alimenta desta API.
