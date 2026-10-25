# Fase 1: entidades e endpoints

Referência do MVP pessoal: o modelo de domínio, as tabelas, como o RLS é ligado à sessão,
a API JSON e as telas.

## Domínio (`domain/`, Java puro)

| Tipo | Pacote | O que é |
|---|---|---|
| `Money` | `money` | Valor em unidades menores (`long`) + `Currency`. Contas com overflow checado; recusa misturar moedas. |
| `Category` | `category` | `id`, `userId`, `name` (1 a 60 caracteres), `type` (`INCOME`/`EXPENSE`), `color` (`#rrggbb`, padrão `#64748b`). `accepts(type)` diz se um lançamento daquele tipo pode usá-la. |
| `Transaction` | `transaction` | `id`, `userId`, `categoryId`, `type`, `amount` (sempre > 0; o sinal vem do tipo), `occurredOn` (1900-01-01 a 2999-12-31), `description` (até 200). |
| `Balance` | `transaction` | Receitas, despesas e `net()` de um conjunto de lançamentos. |
| `Period` | `report` | Intervalo fechado `[from, to]`, no máximo 120 meses. `ofMonth(YearMonth)`, `months()`. |
| `CategorySum`, `MonthlySum` | `report` | Linhas agregadas que saem do banco (por categoria+tipo e por mês+tipo). |
| `Report` | `report` | `Report.of(period, currency, byCategory, byMonth)`: totais, participação de cada categoria em despesas e receitas (`CategoryShare.percent`, 0 a 100, fatia não nula nunca aparece como 0%) e totais de todos os meses do período, inclusive os vazios. |

## Banco

Migrations em `app/src/main/resources/db/migration`.

- `category (id, user_id, name, type, color, created_at)`: único por `(user_id, type, name)`.
- `financial_transaction (id, user_id, category_id, type, amount_cents, currency, occurred_on, description, created_at, updated_at)`.
- Índices: `(user_id, occurred_on DESC)` para o extrato, `(user_id, currency, occurred_on)` para relatórios, `category_id` para a FK.
- `V4` adiciona a cor, limites de tamanho e troca a FK de categoria por uma composta `(category_id, user_id) → category (id, user_id)`. Checagens de FK ignoram RLS, então sem isso um usuário conseguiria apontar para a categoria de outro se soubesse o id.
- `user_id` tem default `app_current_user_id()`.

### Como o RLS é ligado à sessão

1. Toda tabela financeira tem `FORCE ROW LEVEL SECURITY` e a política `user_id = app_current_user_id()`.
2. `RlsTransactionManager` (substitui o `JdbcTransactionManager` do Boot) roda, no início de cada transação,
   `set_config('app.current_user_id', <id do usuário logado>, true)`. O valor vale só para a transação,
   então nada vaza quando a conexão volta para o pool.
3. Spring Data JDBC, jOOQ e `JdbcClient` usam esse gerenciador, então todas as consultas já saem filtradas.
   Os serviços não passam `user_id` em `WHERE`.
4. O Spring Session usa um gerenciador próprio, sem RLS (as tabelas de sessão não têm política, e ler a sessão
   não pode depender do usuário que está sendo lido dela).
5. `RlsGuard` impede a aplicação de subir se o usuário do banco for superusuário ou tiver `BYPASSRLS`.
6. Nos testes, o Postgres do Testcontainers é criado com o mesmo `docker/db/init-app-user.sh` do compose, e a
   aplicação conecta com o usuário comum. O RLS vale em todos os testes.

Sem usuário logado, as consultas simplesmente não retornam nada.

## API JSON

Mesma autenticação das telas: cookie de sessão + token CSRF (header `X-CSRF-TOKEN`, valor na meta tag `_csrf`).
Sem sessão, `/api/**` responde `401` (as telas redirecionam para `/login`). Erros seguem RFC 9457
(`application/problem+json`); erros de validação trazem `errors: {campo: mensagem}`.

Registro de outro usuário responde `404`, igual a um id inexistente.

### Categorias `/api/categories`

| Método | Caminho | Corpo / parâmetros | Respostas |
|---|---|---|---|
| GET | `/api/categories` | `?type=INCOME\|EXPENSE` (opcional) | `200` lista |
| GET | `/api/categories/{id}` | | `200`, `404` |
| POST | `/api/categories` | `{"name": "Mercado", "type": "EXPENSE", "color": "#10b981"}` (`color` opcional) | `201` + `Location`, `400`, `409` nome repetido no mesmo tipo (sem diferenciar maiúsculas) |
| PUT | `/api/categories/{id}` | mesmo corpo | `200`, `400`, `404`, `409` nome repetido ou troca de tipo com lançamentos |
| DELETE | `/api/categories/{id}` | | `204`, `404`, `409` se tiver lançamentos |

Resposta: `{"id": "…", "name": "Mercado", "type": "EXPENSE", "color": "#10b981"}`.

### Lançamentos `/api/transactions`

| Método | Caminho | Corpo / parâmetros | Respostas |
|---|---|---|---|
| GET | `/api/transactions` | `?from=2026-10-01&to=2026-10-31&categoryId=…` (tudo opcional; padrão mês atual) | `200` lista, mais recentes primeiro; `400` período inválido |
| GET | `/api/transactions/{id}` | | `200`, `404` |
| POST | `/api/transactions` | ver abaixo | `201` + `Location`, `400` |
| PUT | `/api/transactions/{id}` | ver abaixo | `200`, `400`, `404` |
| DELETE | `/api/transactions/{id}` | | `204`, `404` |

Corpo:

```json
{
  "type": "EXPENSE",
  "amount": "123.45",
  "currency": "BRL",
  "occurredOn": "2026-10-10",
  "categoryId": "…",
  "description": "Supermercado"
}
```

Regras: `amount` > 0 e com no máximo as casas da moeda (2 para BRL, 0 para JPY); `currency` opcional
(padrão: moeda da conta); `occurredOn` obrigatória e válida; `categoryId` obrigatória, do próprio usuário e
do mesmo tipo do lançamento; `description` até 200 caracteres.

Resposta: `amount` vem como texto decimal (`"123.45"`) para nenhum cliente perder precisão com float, junto
com `amountCents` (inteiro).

### Relatório `/api/reports`

`GET /api/reports?from=…&to=…&categoryId=…` (tudo opcional; padrão mês atual). Valores em centavos, só na
moeda padrão da conta:

```json
{
  "from": "2026-09-01", "to": "2026-10-31", "currency": "BRL",
  "totals": {"incomeCents": 1000000, "expensesCents": 350000, "netCents": 650000},
  "expensesByCategory": [{"categoryId": "…", "name": "Moradia", "color": "#0ea5e9", "totalCents": 300000, "percent": 86}],
  "incomeByCategory": [ … ],
  "months": [{"month": "2026-09", "totals": { … }}, {"month": "2026-10", "totals": { … }}]
}
```

## Telas

| Caminho | O que faz | HTMX |
|---|---|---|
| `/` | Saldo total, receitas/despesas/saldo do mês, rosca de gastos por categoria (SVG gerado no servidor), últimos 5 lançamentos | troca de mês devolve só `#summary` |
| `/transactions` | Extrato do mês agrupado por dia, filtro por categoria, totais | troca de mês/filtro; excluir remove a linha e dispara `transactions-changed`, que recarrega os totais |
| `/transactions/new`, `/transactions/{id}/edit` | Modal (bottom sheet no iPhone) de novo/editar | aberto pelo botão `+`; trocar receita/despesa recarrega as categorias; erros voltam no próprio modal; sucesso responde `HX-Refresh` |
| `/categories` | Criar, editar inline, excluir; botão de categorias sugeridas quando vazio | toda ação devolve a lista atualizada |
| `/reports` | Atalhos (este mês, mês passado, 3 meses, este ano), filtro de período/categoria, totais, despesas e receitas por categoria, barras mês a mês, lançamentos do período | filtros trocam só `#report` e atualizam a URL |

Valores digitados no formulário aceitam `1.234,56`, `1234,56` ou `1234.56`.

## Login com Google

`AppOidcUserService` vincula a conta Google a `app_user` via `user_identity`: na primeira vez, liga a uma conta
existente com o mesmo e-mail (só se o Google confirmou o e-mail) ou cria uma conta nova.
