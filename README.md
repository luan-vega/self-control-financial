# Self Control Financial

Aplicativo de controle financeiro pessoal. Web app em Java, renderizado no servidor e usado no iPhone como PWA (Safari → Compartilhar → Adicionar à Tela de Início). Multiusuário desde o início, pensado para uso comercial no futuro.

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
| Autenticação | Spring Security: e-mail/senha com Argon2id + Google OAuth2, sessão no banco (Spring Session JDBC) |
| Testes | JUnit 5, AssertJ, Testcontainers (Postgres real) |
| CI | GitHub Actions (`./mvnw verify`) |

## Arquitetura

```
navegador (iPhone / desktop)
   │  cookie de sessão + CSRF, HTML e fragmentos HTMX
   ▼
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

**Decisões importantes**

- **Dinheiro é `bigint` em centavos + moeda (`char(3)`).** Nunca `double`/`float`. No código, `Money` faz as contas com overflow checado e recusa somar moedas diferentes.
- **Row-Level Security no Postgres.** As tabelas financeiras têm `FORCE ROW LEVEL SECURITY` e uma política `user_id = app_current_user_id()`. A aplicação define o usuário por transação com `set_config('app.current_user_id', ...)`. O usuário do banco da aplicação **não pode ser superusuário nem ter `BYPASSRLS`** (superusuário ignora RLS). O `compose.yaml` já cria um usuário comum, e `RowLevelSecurityTests` prova o isolamento.
- **BFF server-side.** O navegador só fala com a app (sessão + cookie). Os controllers dependem de interfaces `*Client`, então dá para extrair serviços depois (núcleo financeiro, importação, notificações, IA) sem reescrever as telas. Começamos como monólito modular.
- **HTMX para interatividade.** O mesmo endpoint devolve a página inteira ou só um fragmento quando recebe o header `HX-Request` (ex.: trocar o mês no início). O token CSRF vai em toda requisição HTMX (`static/js/app.js`).
- **Tailwind compilado no build.** O `frontend-maven-plugin` baixa o Node em `app/target/node` e gera `static/css/app.css`. Não precisa ter Node instalado.

## Rodando localmente

Pré-requisitos: JDK 25 e Docker.

```bash
# 1. Banco
docker compose up -d db

# 2. App com o perfil dev (cria o usuário dev@selfcontrol.local / dev12345)
./mvnw -pl app -am spring-boot:run -Dspring-boot.run.profiles=dev
```

Abra http://localhost:8080. Para testar no iPhone na mesma rede, use o IP da máquina (o PWA completo exige HTTPS, o que fica para o deploy).

Tudo em containers: `docker compose --profile app up --build`.

Enquanto edita templates, rode `cd app && npm run watch:css` em outro terminal para o Tailwind recompilar.

### Login com Google

Ative o perfil `google` e defina `GOOGLE_CLIENT_ID` e `GOOGLE_CLIENT_SECRET`. A redirect URI no Google Cloud é `{baseUrl}/login/oauth2/code/google`. Sem o perfil, o botão do Google simplesmente não aparece.

### Variáveis de ambiente

| Variável | Padrão |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/selfcontrol` |
| `DB_USER` / `DB_PASSWORD` | `selfcontrol` / `selfcontrol` |
| `COOKIE_SECURE` | `true` (o perfil `dev` desliga) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | só com o perfil `google` |

## Testes

```bash
./mvnw verify
```

Os testes do `app` sobem um Postgres real com Testcontainers, então o Docker precisa estar rodando.

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
│       │   ├── auth/            # login, usuários
│       │   ├── config/          # Spring Security
│       │   ├── dashboard/       # tela inicial (stub com dados de exemplo)
│       │   └── shared/web/      # formatação para os templates
│       └── resources/
│           ├── db/migration/    # Flyway
│           ├── templates/       # Thymeleaf
│           └── static/          # manifest, service worker, ícones, JS
├── compose.yaml                 # Postgres local (+ app opcional)
├── docker/db/                   # cria o usuário da app sem privilégios
├── Dockerfile
└── .github/workflows/ci.yml
```

## Roadmap

- **Fase 0, Fundação** (este repositório agora): estrutura, segurança, banco com RLS, CI, layout do PWA. Tela inicial com dados de exemplo.
- **Fase 1, MVP pessoal**: CRUD de lançamentos e categorias, filtro de RLS ligado à sessão, relatórios básicos com jOOQ (codegen a partir do schema), vínculo do login Google com `app_user`.
- **Fase 2, Automação**: importação OFX/CSV e captura rápida via Atalhos do iOS (webhook).
- **Fase 3, Comercial**: API REST pública, cadastro aberto, apps nativos.
