# Casos de erro — comportamento APÓS fix de segurança #15/#6/#9

> Atualizado 2026-07-03 (pós-fix). Status HTTP corrigidos e mensagens sem
> vazamento de internals. Verificado localmente contra o jar buildado.

## Senha de certificado errada → 400 (inalterado, já estava correto)
`POST /certificate/info` (cert válido, senha errada)
```json
{ "success": false, "error": "Incorrect certificate password", "code": "CERTIFICATE_ERROR" }
```

## Request sem multipart → 400 (era 500) ✅
`POST /sign` sem body multipart
```json
{ "success": false, "error": "Malformed multipart request or missing file part", "code": "BAD_REQUEST" }
```

## Método errado (GET em rota POST) → 405 (era 500) ✅
`GET /sign`
```json
{ "success": false, "error": "HTTP method not allowed on this endpoint", "code": "METHOD_NOT_ALLOWED" }
```

## PDF corrompido no /verify/pdf → 400 (era 500) ✅
`POST /verify/pdf` com PDF malformado (header %PDF mas estrutura quebrada)
```json
{ "success": false, "error": "Invalid or corrupted PDF document", "code": "INVALID_DOCUMENT" }
```

## Arquivo que não é PDF → 400 ✅
`POST /verify/pdf` com .txt
```json
{ "success": false, "error": "Not a PDF file (missing %PDF header)", "code": "INVALID_DOCUMENT" }
```

## Assinatura destacada NÃO bate → 200 valid:false (era 500) ✅
`POST /verify` (assinatura de outro documento) — adulteração é resultado de
negócio, não erro de servidor.
```json
{ "valid": false, "filename": "manifestacao2.pdf" }
```
Assinatura correta → `{ "valid": true, "filename": "..." }` (200).

## Erro interno genuíno → 500 sem vazar internals (#6) ✅
Mensagem genérica + correlationId; detalhe (lib de cripto/keystore) só no log.
```json
{ "success": false, "error": "Internal server error. Reference: <uuid>", "code": "INTERNAL_ERROR" }
```

## ITI — inalterado (aguarda decisão #16)
`/verify/iti` produção → 502; staging → falso-positivo (HTML da homepage).
Ainda não corrigido; ver TODO-SECURITY.md #16.
