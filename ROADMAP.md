# Roadmap

O que já existe, o que vem a seguir e o que está fora de escopo por enquanto. Datas são intenções,
não promessas.

## Feito

- **Fase 0, Fundação**: estrutura multi-módulo, Spring Security, Postgres com Row-Level Security, CI, layout do PWA.
- **Fase 1, MVP pessoal**: lançamentos e categorias (telas e API), dashboard, relatórios com jOOQ, login com Google.
- **Fase 2, Automação**: importação OFX/CSV com detecção de duplicatas, caixa de entrada, webhook para os Atalhos do iPhone.
- **Fase 3, Comercial**: cadastro aberto, perfil, administração, planos Free e Pro com Stripe, API pública
  `/api/v1` com chave de API e OpenAPI, rate limit, HTTPS obrigatório, auditoria, imagem Docker, Kubernetes e CI/CD.

## Próximo (v1.x)

- Envio real de e-mail (SES, Postmark ou Resend) no lugar do log
- Exclusão de conta e exportação de dados (CSV/JSON) pela própria tela
- Plano anual e cupons no Stripe
- Rate limit e cache compartilhados (Redis) para rodar mais de uma réplica
- Codegen do jOOQ a partir do schema
- Orçamentos por categoria com aviso ao passar do limite
- Contas (carteira, conta corrente, cartão) e transferências entre elas

## Depois (v2)

- Servidor OAuth2 próprio para apps de terceiros (client credentials, escopos por consentimento)
- Apps nativos (iOS e Android) sobre a API pública
- Notificações push
- Analytics avançado (projeções, recorrências)
- Categorização automática com IA
- Integração direta com bancos (Open Finance)

## Fora de escopo

- Investimentos e cotações
- Emissão de notas fiscais ou contabilidade de empresa

Sugestões são bem-vindas em issues no GitHub.
