# prc_signer_a1 — Instruções do Projeto

Assinador de documentos com certificado A1. Backend Java/Spring (`src/`),
frontend Svelte 5 + Vite (`frontend/`). Full design guide em `.impeccable.md`.

## Design Context

### Users
Advogados e equipe jurídica que já usam certificados A1 mas têm dificuldade com
ferramentas online. A página é uma single/landing page de marketing de utilidade:
entrega valor (assinar PDF) e apresenta o ProcStudio. Precisa ser responsiva no
celular. Após assinar, mostrar CTA "Conheça o sistema ProcStudio e deixe suas
configurações salvas".

### Brand Personality
Confiável e profissional. Três palavras: confiável, profissional, simples.
Sóbrio, institucional, credibilidade jurídica. Reduzir ansiedade de não-técnicos.

### Aesthetic Direction
Sistema ProcStudio (tokens em `frontend/src/app.css`): navy `#0f1a3e`, azul de
marca `#0088FF`, escala de cinzas, fonte system stack. Card central com borda-topo
azul. Light + dark mode via `prefers-color-scheme`. Favicon oficial em
`ProcStudio-Docker/customer-frontend/public/favicon.ico`. Anti-referência:
ferramenta técnica crua/genérica de TI.

### Design Principles
1. Confiança acima de tudo — é assinatura jurídica; estados de sucesso/erro claros.
2. Simplicidade para não-técnicos — fluxo óbvio, português claro, sem jargão.
3. Mobile-first responsivo.
4. Light + dark mode via tokens CSS semânticos (nunca cores hardcoded).
5. Converter visitantes — CTA pós-assinatura para o sistema ProcStudio.

### Accessibility
WCAG AA para contraste nos dois temas. Respeitar `prefers-reduced-motion`. Sem
requisito de navegação por teclado (decisão de produto). Labels em todos os inputs.
