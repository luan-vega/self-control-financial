# Contribuindo

Obrigado pelo interesse! Este guia cobre como rodar o projeto, as convenções do código e o que um PR
precisa ter para entrar.

## Rodando

Pré-requisitos: **JDK 25** e **Docker** (o banco local e os testes usam Postgres em container).

```bash
docker compose up -d db
./mvnw -pl app -am spring-boot:run -Dspring-boot.run.profiles=dev   # http://localhost:8080
./mvnw verify                                                      # testes + cobertura
```

O perfil `dev` cria `dev@selfcontrol.local` / `dev12345` (administrador) com dados de exemplo.
Enquanto mexe em templates, `cd app && npm run watch:css` recompila o Tailwind.

## Como o código é organizado

- `domain/`: regras de negócio em **Java puro**. O build quebra se alguém importar Spring, Jakarta ou jOOQ aqui.
- `app/`: Spring Boot, **um pacote por funcionalidade** (`transaction`, `billing`, `admin`...), com
  controller → client/service → repository dentro de cada um. O que é transversal fica em `shared/`.
- Telas em Thymeleaf + HTMX. O mesmo endpoint devolve a página inteira ou só o fragmento quando recebe `HX-Request`.
- Sem JavaScript inline (a CSP bloqueia). Comportamento no navegador vai em `static/js/app.js`, ligado por atributos `data-*`.

## Regras que não se negociam

- **Dinheiro** é `bigint` em centavos + moeda. Nunca `double`, `float` ou `BigDecimal` no banco.
- **Toda tabela com dado financeiro tem RLS** (`ENABLE` + `FORCE ROW LEVEL SECURITY` e política
  `user_id = app_current_user_id()`). Não filtre por `user_id` na mão nessas tabelas: se o teste
  de isolamento passar só por causa de um `WHERE`, o RLS está errado.
- **Migrations só para frente.** Nunca edite uma `V*.sql` que já está na `main`; crie a próxima.
  A migration nova precisa funcionar com a versão anterior da app rodando (deploy sem parada).
- **Segredos só em variável de ambiente.** Nada de chave, senha ou token em `*.properties`, testes ou commits.
- **Ação que muda conta, dinheiro ou acesso grava auditoria** (`AuditLog.record`).
- **Endpoint novo da API pública** (`/api/v1`) precisa de teste de isolamento entre contas e entra na
  [docs/api.md](docs/api.md). Não quebre o contrato da v1: campo novo pode, renomear ou remover não.

## Testes

- Testes de integração estendem `IntegrationTest` (Postgres real via Testcontainers, usuário sem
  privilégios, então o RLS vale). Use `newUser()`, `as(user, ...)`, `apiKey(user, readOnly)` e
  `api(request, key)`.
- Regras puras ficam no `domain` com JUnit simples.
- Cobertura mínima de **80% de linhas por módulo** (JaCoCo quebra o build abaixo disso).
- Não desative nem pule teste para o build passar.

## Commits e PRs

- Branch a partir da `main`, um assunto por PR.
- Mensagem de commit no imperativo, em português, curta na primeira linha (`Adiciona limite do plano Free na API`).
- O PR descreve o que muda para quem usa e como foi testado. Mudança de tela: inclua um print do iPhone (390 px).
- O CI (`./mvnw verify` + build da imagem) precisa estar verde.

## Segurança

Achou uma vulnerabilidade? **Não abra issue pública.** Mande um e-mail para o mantenedor
(o endereço está no perfil do GitHub) com os passos para reproduzir.
