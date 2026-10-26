# Self Control Financial

Aplicativo de controle financeiro pessoal. Web app em Java, renderizado no servidor e usado no iPhone como PWA (Safari → Compartilhar → Adicionar à Tela de Início). Multiusuário, com cadastro aberto, planos Free e Pro e uma API pública para integrações.

## Stack

| Camada | Escolha |
|---|---|
| Linguagem / runtime | Java 25 |
| Framework | Spring Boot 4.1 |
| Build | Maven multi-module (`domain` + `app`), Maven Wrapper |
| Front | Thymeleaf + HTMX 2 + Tailwind CSS 4 (PWA) |
| Banco | PostgreSQL 18 com Row-Level Security por `user_id` |
| Acesso a dados | Spring Data JDBC (CRUD) + jOOQ (relatórios) |
| Migrations | Flyway, só para frente |
| Autenticação | Spring Security: e-mail/senha com Argon2id + Google OAuth2, sessão no banco (Spring Session JDBC); API pública com chave de API ou ID token do Google |
| API pública | REST/JSON em `/api/v1`, OpenAPI 3 (springdoc) com Swagger UI em `/docs` |
| Cobrança | Stripe (Checkout, Customer Portal e webhook), ou um provedor mock para desenvolvimento |
| Cache | Caffeine (relatórios) |
| Testes | JUnit 5, AssertJ, Testcontainers (Postgres real) |
| CI/CD | GitHub Actions: testes, imagem no GHCR e deploy manual no Kubernetes |
| Deploy | Docker (imagem em camadas, sem root) e manifests Kubernetes |

## Arquitetura

```
navegador (iPhone / desktop)          integrações / apps        Stripe, Atalhos do iOS
   │  cookie de sessão + CSRF             │  Authorization: Bearer      │  webhooks assinados
   │  HTML e fragmentos HTMX              │  /api/v1 (JSON)             │
   ▼                                      ▼                             ▼
app ── controllers (BFF: monta telas, devolve página inteira ou fragmento HTMX)
   │       │
   │       ▼
   │   clients (interfaces, ex.: DashboardClient)  ← hoje em processo; amanhã HTTP para um serviço
   │       │
   │       ▼
   │   services  ──►  domain (Java puro: Money, Transaction, Balance, Category)
   │       │
   │       ▼
   │   repositories (Spring Data JDBC / jOOQ)
   ▼
PostgreSQL (RLS: cada usuário só enxerga as próprias linhas)
```

**Módulos**

- `domain/`: regras de negócio em Java puro, sem nenhum framework. O `maven-enforcer-plugin` quebra o build se alguém adicionar Spring, Jakarta ou jOOQ aqui. Testado só com JUnit.
- `app/`: aplicação Spring Boot, organizada por funcionalidade (`auth`, `dashboard`, ...) e não por camada técnica. Dentro de cada pacote: controller → client/service → repository.

Detalhes das entidades, do RLS e de todos os endpoints: [docs/fase-1.md](docs/fase-1.md). Importação, caixa de entrada e webhook dos Atalhos: [docs/fase-2.md](docs/fase-2.md). Cadastro, planos, administração e segurança: [docs/fase-3.md](docs/fase-3.md). API pública: [docs/api.md](docs/api.md). Deploy: [docs/deployment.md](docs/deployment.md).

**Decisões importantes**

- **Dinheiro é `bigint` em centavos + moeda (`char(3)`).** Nunca `double`/`float`. No código, `Money` faz as contas com overflow checado e recusa somar moedas diferentes.
- **Row-Level Security no Postgres, ligado à sessão.** As tabelas financeiras têm `FORCE ROW LEVEL SECURITY` e uma política `user_id = app_current_user_id()`. O `RlsTransactionManager` grava o usuário logado com `set_config('app.current_user_id', ..., true)` no início de toda transação, então nenhum serviço filtra por `user_id` na mão. O usuário do banco da aplicação **não pode ser superusuário nem ter `BYPASSRLS`** (superusuário ignora RLS): o `compose.yaml` cria um usuário comum, os testes usam o mesmo script, e a aplicação se recusa a subir se o usuário ignorar RLS.
- **BFF server-side.** O navegador só fala com a app (sessão + cookie). Os controllers dependem de interfaces `*Client`, então dá para extrair serviços depois (núcleo financeiro, importação, notificações, IA) sem reescrever as telas. Começamos como monólito modular.
- **Relatórios com jOOQ** (SQL tipado, ainda sem codegen) e CRUD com Spring Data JDBC. As contas (participação por categoria, meses vazios) ficam no `domain`.
- **Gráficos em SVG gerado no servidor**, sem biblioteca de gráficos no navegador.
- **HTMX para interatividade.** O mesmo endpoint devolve a página inteira ou só um fragmento quando recebe o header `HX-Request` (ex.: trocar o mês no início). O token CSRF vai em toda requisição HTMX (`static/js/app.js`).
- **Uma API, várias portas de entrada.** Os mesmos controllers JSON respondem em `/api/...` (sessão + CSRF, para as telas) e em `/api/v1/...` (chave de API, sem sessão, para integrações). Cada porta tem a própria cadeia do Spring Security, e o RLS vale igual nas duas.
- **Estado em memória enquanto for uma instância.** Rate limit e cache de relatórios não precisam de Redis com uma réplica; o caminho para escalar está em [docs/deployment.md](docs/deployment.md).
- **Tailwind compilado no build.** O `frontend-maven-plugin` baixa o Node em `app/target/node` e gera `static/css/app.css`. Não precisa ter Node instalado.

## Rodando localmente

Pré-requisitos: JDK 25 e Docker.

```bash
# 1. Banco
docker compose up -d db

# 2. App com o perfil dev (cria o usuário dev@selfcontrol.local / dev12345)
./mvnw -pl app -am spring-boot:run -Dspring-boot.run.profiles=dev
```

Abra http://localhost:8080 e entre com `dev@selfcontrol.local` / `dev12345` (administrador), ou crie uma conta em `/register` (o link de confirmação aparece no log). O perfil `dev` também cria categorias sugeridas e lançamentos de exemplo no mês atual e no anterior. Para testar no iPhone na mesma rede, use o IP da máquina (o PWA completo exige HTTPS, o que fica para o deploy).

Tudo em containers: `docker compose --profile app up --build`.

Enquanto edita templates, rode `cd app && npm run watch:css` em outro terminal para o Tailwind recompilar.

### Login com Google

Ative o perfil `google` e defina `GOOGLE_CLIENT_ID` e `GOOGLE_CLIENT_SECRET`. A redirect URI no Google Cloud é `{baseUrl}/login/oauth2/code/google`. Sem o perfil, o botão do Google simplesmente não aparece.

### Variáveis de ambiente

As principais:

| Variável | Padrão |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/selfcontrol` |
| `DB_USER` / `DB_PASSWORD` | `selfcontrol` / `selfcontrol` |
| `COOKIE_SECURE` | `true` (o perfil `dev` desliga) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | só com o perfil `google` |
| `ADMIN_EMAILS` | e-mails que viram administradores |
| `BILLING_PROVIDER` | `mock` (ou `stripe`, com `STRIPE_SECRET_KEY`, `STRIPE_PRICE_ID` e `STRIPE_WEBHOOK_SECRET`) |
| `APP_PUBLIC_URL` | URL pública usada nos e-mails e no retorno do Stripe |

A lista completa, com HTTPS, CORS e limites, está em [docs/deployment.md](docs/deployment.md#variáveis-de-ambiente).

## Usando o MVP

- **Início**: saldo total, receitas e despesas do mês, gastos por categoria e últimos lançamentos. As setas trocam o mês.
- **+ (botão central)**: novo lançamento. Escolha despesa ou receita, valor (`1.234,56`), data e categoria.
- **Extrato**: lançamentos do mês agrupados por dia, com filtro por categoria. Toque em um lançamento para editar; a lixeira exclui.
- **Relatórios**: atalhos de período, filtro de datas e categoria, despesas e receitas por categoria, evolução mês a mês.
- **Categorias**: crie, renomeie, troque a cor ou exclua. Uma conta nova pode criar as categorias sugeridas com um toque. Categorias com lançamentos não podem ser excluídas.

- **Importar** (no extrato ou na caixa de entrada): arraste um OFX ou CSV do banco, revise o preview (novas, duplicadas, com erro) e envie para a caixa de entrada.
- **Caixa de entrada** (ícone no início, com o número de pendentes): escolha a categoria, edite, confirme ou descarte em lote. Só o que você confirma entra nas contas.
- **Atalho do iPhone**: gere o token e configure a automação da Carteira para cada compra no cartão cair na caixa de entrada. Passo a passo em [docs/fase-2.md](docs/fase-2.md).

- **Configurações** (sua foto no canto do início): perfil, foto, senha, plano, chaves de API e, para administradores, a lista de contas e a auditoria.
- **Planos**: o Free tem até 100 lançamentos por mês; o Pro não tem limite. Assinatura pelo Stripe em `/billing`.

Para integrar com outros apps, crie uma chave em Configurações → Chaves de API e use a API pública em `/api/v1` ([docs/api.md](docs/api.md), Swagger em `/docs`).

## Testes

```bash
./mvnw verify
```

Os testes do `app` sobem um Postgres real com Testcontainers, então o Docker precisa estar rodando. O app conecta nesse Postgres com um usuário sem privilégios, então o RLS vale em todos os testes.

O JaCoCo gera o relatório de cobertura em `domain/target/site/jacoco` e `app/target/site/jacoco` e quebra o build se a cobertura de linhas de qualquer módulo ficar abaixo de 80%.

## Estrutura

```
.
├── pom.xml                      # parent: versões e módulos
├── domain/                      # Java puro + JUnit
├── app/
│   ├── package.json             # build do Tailwind
│   └── src/main/
│       ├── frontend/app.css     # entrada do Tailwind
│       ├── java/com/selfcontrol/app/
│       │   ├── auth/            # login (senha e Google), usuários, seeder do perfil dev
│       │   ├── category/        # categorias: serviço, API JSON e tela
│       │   ├── transaction/     # lançamentos: serviço, API JSON, extrato e modal
│       │   ├── report/          # relatórios com jOOQ: serviço, API JSON e tela
│       │   ├── dashboard/       # tela inicial
│       │   ├── importing/       # import OFX/CSV: parsers, preview, confirmação, histórico
│       │   ├── inbox/           # caixa de entrada (pendentes de import e webhook)
│       │   ├── webhook/         # webhook dos Atalhos do iOS: token, filtro, tela de configuração
│       │   ├── account/         # cadastro, confirmação de e-mail, senha, perfil, termos
│       │   ├── admin/           # lista de contas, ativar/desativar, auditoria
│       │   ├── api/             # autenticação da API pública (chave e Google), /api/v1/me
│       │   ├── apikey/          # chaves de API: serviço e tela
│       │   ├── billing/         # planos, Stripe (ou mock), webhook, tela de cobrança
│       │   ├── config/          # Spring Security (cadeias da tela, API pública e webhooks), OpenAPI
│       │   └── shared/          # RLS (db), usuário atual (security), auditoria, rate limit, erros, logs e HTTPS (web)
│       └── resources/
│           ├── db/migration/    # Flyway
│           ├── templates/       # Thymeleaf
│           └── static/          # manifest, service worker, ícones, JS
├── compose.yaml                 # Postgres local (+ app opcional)
├── docker/db/                   # cria o usuário da app sem privilégios (usado também nos testes)
├── docs/fase-1.md               # entidades, RLS, endpoints e telas
├── docs/fase-2.md               # importação, caixa de entrada, webhook e solução de problemas
├── docs/fase-3.md               # cadastro, planos, administração, segurança
├── docs/api.md                  # API pública /api/v1
├── docs/deployment.md           # Docker, Kubernetes, Stripe, variáveis de ambiente
├── deploy/k8s/                  # manifests Kubernetes (kustomize)
├── Dockerfile                   # build multi-stage, imagem em camadas, sem root
├── CONTRIBUTING.md
├── ROADMAP.md
└── .github/workflows/           # ci.yml, release.yml (imagem no GHCR), deploy.yml (manual)
```

## Roadmap

- **Fase 0, Fundação** (feita): estrutura, segurança, banco com RLS, CI, layout do PWA.
- **Fase 1, MVP pessoal** (feita): CRUD de lançamentos e categorias, dashboard e relatórios com jOOQ, login Google.
- **Fase 2, Automação** (feita): importação OFX/CSV, caixa de entrada e webhook para os Atalhos do iOS.
- **Fase 3, Comercial** (feita): cadastro aberto, perfil, admin, planos Free/Pro com Stripe, API pública, rate limit, auditoria, Docker, Kubernetes e CI/CD.

O que vem depois está no [ROADMAP.md](ROADMAP.md). Para contribuir, veja o [CONTRIBUTING.md](CONTRIBUTING.md).
