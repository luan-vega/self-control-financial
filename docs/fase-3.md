# Fase 3: comercial

Cadastro aberto, perfil, administração, planos Free e Pro com Stripe, API pública e o que é preciso
para rodar isso como um produto: HTTPS obrigatório, rate limit, logs estruturados, trilha de auditoria,
imagem Docker, Kubernetes e CI/CD.

Detalhes da API: [api.md](api.md). Como subir em produção: [deployment.md](deployment.md).

## Telas novas

| Caminho | O que faz |
|---|---|
| `/register` | Cadastro com nome, e-mail, senha (mín. 8) e moeda padrão. Exige aceitar os Termos e a Política. Manda o e-mail de confirmação. |
| `/verify-email?token=` | Link do e-mail. Vale 3 dias e uma vez só. |
| `/forgot-password`, `/reset-password` | Troca de senha por e-mail. O link vale 1 hora; a troca encerra todas as sessões da conta. A resposta é a mesma para e-mail que existe ou não. |
| `/settings` | Hub de configurações: perfil, plano, chaves de API, Atalho do iPhone, administração (só admin) e sair. |
| `/settings/profile` | Nome, moeda padrão, foto (PNG, JPEG ou WebP até 512 KB; o tipo é conferido pelos bytes, não pela extensão), reenviar confirmação, trocar senha. |
| `/settings/api-keys` | Criar (normal ou somente leitura), listar e revogar chaves da API pública. |
| `/billing` | Plano atual, uso do mês, assinar o Pro, gerenciar ou cancelar. |
| `/admin/users` | Admin: números da plataforma, busca por nome ou e-mail, ativar e desativar contas. |
| `/admin/audit` | Admin: trilha de auditoria, geral ou de uma conta. |
| `/legal/terms`, `/legal/privacy` | Termos de Uso e Política de Privacidade (públicas). |
| `/legal/accept` | Quem entrou antes de uma versão nova dos termos aceita aqui antes de continuar. |
| `/docs` | Swagger UI da API pública. |

No início, a foto (ou as iniciais) no canto leva às configurações. Quem está no Free vê um aviso quando
passa de 80% do limite do mês.

## Contas e papéis

- `app_user` ganhou `role` (`USER` ou `ADMIN`), `active`, `email_verified_at`, `terms_accepted_at` e
  `terms_version`, `avatar_url` (foto do Google), `last_login_at`.
- **Admin**: os e-mails em `ADMIN_EMAILS` viram admin quando a app sobe ou quando a conta é criada.
  O admin não vê dados financeiros de ninguém (o RLS vale para ele também), só a lista de contas e a auditoria.
- **Desativar** uma conta encerra todas as sessões dela, bloqueia o login (senha e Google), as chaves de
  API e o webhook do Atalho. Ninguém consegue desativar a própria conta.
- **Confirmação de e-mail** não bloqueia o uso: a conta funciona na hora, e o perfil lembra de confirmar.
  Contas do Google já entram confirmadas. Os e-mails por enquanto vão para o log (`LogMailer`).
- **Termos**: a versão atual fica em `Legal.VERSION`. Mudou a versão, todo mundo passa por `/legal/accept`
  no próximo acesso (a API pública não é bloqueada).

## Planos

| | Free | Pro |
|---|---|---|
| Lançamentos criados por mês | 100 | ilimitado |
| Tudo o mais (relatórios, import, caixa de entrada, API, Atalho) | sim | sim |

- O limite conta lançamentos criados no mês corrente (`created_at`), por qualquer caminho: tela, API ou
  confirmação da caixa de entrada. Importar para a caixa de entrada não conta; confirmar conta.
- Passou do limite: a tela mostra o aviso com o link para o Pro, e a API responde `402`.
- Editar e excluir nunca são bloqueados, nem a leitura dos dados.
- Pro cancelado continua Pro até o fim do período pago. Pagamento falhou: fica `PAST_DUE` e continua Pro
  enquanto o Stripe tenta cobrar de novo; se a assinatura for cancelada, volta para o Free.

### Provedores de cobrança

- `mock` (padrão): o botão "Assinar" abre uma tela de checkout falsa que ativa o Pro na hora. Serve para
  desenvolvimento e demonstração. Nada é cobrado.
- `stripe`: Stripe Checkout para assinar, Customer Portal para gerenciar e o webhook
  `POST /api/billing/webhook` para manter `subscription` em dia. Sem SDK: chamadas REST com `RestClient`.
  Configuração em [deployment.md](deployment.md#stripe).

O webhook confere a assinatura `Stripe-Signature` (HMAC-SHA256 com tolerância de 5 minutos) e grava o
id de cada evento em `billing_event`, então um evento reenviado não vale duas vezes.

## Segurança

- **HTTPS obrigatório** no perfil `prod`: GET e HEAD em HTTP recebem 308 para HTTPS, o resto 403.
  Cookie de sessão `Secure`, `HttpOnly`, `SameSite=Lax`. HSTS pelo Spring Security.
- **Headers**: Content-Security-Policy (só scripts e estilos do próprio domínio), `Referrer-Policy`,
  `Permissions-Policy`, `X-Content-Type-Options`, `X-Frame-Options: DENY`.
- **CSRF** em todas as telas e na API da sessão. A API pública e os webhooks não usam cookie, então não
  precisam (e não aceitam sessão).
- **Rate limit** (token bucket em memória):

  | O quê | Limite | Chave |
  |---|---|---|
  | API pública | 100/min | usuário |
  | API pública, antes de autenticar | 300/min | IP |
  | Login, cadastro, esqueci a senha | 10/min | IP |
  | Webhook do Atalho | 60/min | token |

- **Senhas** com Argon2id; tokens de e-mail, chaves de API e token do webhook guardados só como SHA-256.
- **Segredos** só em variáveis de ambiente.
- **Logs estruturados** (ECS em JSON no `prod`) com `X-Request-Id` e id do usuário em todas as linhas.
- **Auditoria** em `audit_event`, só inserção (um trigger recusa UPDATE e DELETE).

## Banco (V6)

[`V6__commercial.sql`](../app/src/main/resources/db/migration/V6__commercial.sql):

- Colunas novas em `app_user`; contas existentes ficam com o e-mail confirmado.
- Tabelas: `user_avatar`, `user_token`, `api_key`, `subscription`, `billing_event`, `audit_event`.
- Índices: `financial_transaction (user_id, created_at)` para contar o uso do mês; o índice de relatórios
  passou a cobrir `type`, `amount_cents` e `category_id` (`INCLUDE`), o que permite index-only scan;
  índice parcial dos pendentes da caixa de entrada.

Essas tabelas não têm RLS porque são lidas antes de haver um usuário no contexto (login, link do e-mail,
chave de API, webhook do Stripe). As consultas das telas filtram por `app_current_user_id()`.

## Cache de relatórios

Relatórios e o saldo total ficam em cache (Caffeine) por usuário e parâmetros, por até 10 minutos.
Criar, editar ou excluir um lançamento ou uma categoria limpa o cache daquele usuário depois do commit,
então a tela nunca mostra número velho depois de uma alteração feita na mesma instância.

## Testes

| Classe | Cobre |
|---|---|
| `PublicApiTests` | CRUD por JSON com chave, caixa de entrada, chave somente leitura, chave inválida/revogada, sessão não abre a API, CORS, conta desativada |
| `MultiUserIsolationTests` | Uma conta não lê, altera, exclui nem usa nada de outra pela API; admin também não |
| `RateLimitTests`, `RateLimiterTest` | 429 com `Retry-After`, limite por usuário somando chaves, limite de login por IP, recarga do balde |
| `StripeWebhookTests`, `StripeSignatureTest` | Assinatura válida, inválida, vencida; evento repetido; ciclo de assinatura e cancelamento |
| `BillingTests` | Limite do Free na tela e na API, checkout mock, Pro sem limite |
| `RegistrationTests`, `ProfileTests` | Cadastro, confirmação, troca de senha, perfil, foto |
| `AdminTests`, `AuditTests` | Acesso só para admin, desativar conta, trilha de auditoria só de inserção |
| `OpenApiAndHeadersTests`, `RequireHttpsFilterTest` | Swagger, headers de segurança, redirecionamento para HTTPS |

## Decisões e o que ficou para depois

- **Sem servidor OAuth2 próprio** por enquanto: chave de API e ID token do Google cobrem integrações e apps
  com login. Um authorization server (Spring Authorization Server) entra quando houver apps de terceiros.
- **Sem Redis**: com uma instância, rate limit e cache em memória são exatos e mais simples. O caminho para
  várias réplicas está em [deployment.md](deployment.md#escalando-para-mais-de-uma-réplica).
- **E-mail em log** até escolher o provedor.
- **Exclusão de conta** é feita a pedido, pelo suporte (está na Política de Privacidade). Uma tela de
  autoatendimento entra junto com a exportação de dados.
- Fora desta fase, como combinado: notificações push, analytics avançado, IA e integração direta com bancos.
