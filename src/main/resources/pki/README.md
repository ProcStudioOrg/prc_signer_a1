# Truststore ICP-Brasil

`icp-brasil-roots.pem` — as 12 raízes públicas da ICP-Brasil (v2 a v13), baixadas
do repositório oficial do ITI em 2026-09-06:

    https://acraiz.icpbrasil.gov.br/credenciadas/RAIZ/ICP-Brasilv<N>.crt

Cada arquivo foi conferido individualmente antes de entrar no bundle: todas são
raízes autoassinadas (`subject == issuer`). São certificados **públicos** — não
há segredo aqui, e por isso versionar é seguro e desejável (o build fica
reprodutível e não depende de a rede do ITI estar no ar).

## Atenção ao mexer

O `CertificateChainVerifier` parseia **um certificado por vez**. Pelo menos uma
destas raízes usa chave EC com parâmetros explícitos, que o provider padrão da
JDK recusa com `Only named ECParameters supported`. Um `generateCertificates`
sobre o bundle inteiro devolve **zero** âncoras por causa dela — e aí todo
certificado legítimo vira `unverified` em silêncio, o que parece "truststore não
configurado". O BouncyCastle (já é dependência) lê a que a JDK recusa.

## Atualizar

Quando o ITI publicar uma raiz nova (vN+1), baixe, confira que é autoassinada e
concatene. Raiz expirada pode ficar: PKIX ignora âncora fora da validade para o
caminho, e remover quebraria a verificação de certificados antigos ainda válidos.
