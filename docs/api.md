# API pública (v1)

API REST em JSON para integrar o Self Control Financial com outros apps, planilhas e automações.
A documentação interativa (OpenAPI 3 / Swagger UI) fica em **`/docs`** na própria instância, e o
contrato em JSON em **`/v3/api-docs`**.

- Base: `https://<sua-instância>/api/v1`
- Formato: JSON (UTF-8). Erros em [`application/problem+json`](https://www.rfc-editor.org/rfc/rfc9457).
- Sem sessão, cookie nem CSRF: toda requisição leva o header `Authorization`.

## Autenticação

### Chave de API (recomendado)

Crie em **Configurações → Chaves de API**. A chave (`scfk_...`) aparece uma única vez; o servidor
guarda só o SHA-256. Cada conta pode ter até 10 chaves ativas, e cada uma pode ser **somente leitura**.

```bash
curl https://financas.exemplo.com/api/v1/me \
  -H "Authorization: Bearer scfk_SUA_CHAVE"
```

Revogar a chave (ou desativar a conta) corta o acesso na hora.

### Google (ID token)

Apps que já fazem login com Google podem mandar o **ID token** do usuário no lugar da chave:
`Authorization: Bearer eyJhbGciOi...`. O token precisa ser emitido para um dos client ids em
`API_GOOGLE_CLIENT_IDS`, estar dentro da validade e pertencer a uma conta que já entrou no app com
esse Google ao menos uma vez. Dá acesso de leitura e escrita.

> Um servidor OAuth2 próprio (client credentials, refresh token, consentimento por escopo) está no
> [roadmap](../ROADMAP.md). Até lá, chaves de API cobrem integrações de servidor e o ID token do Google
> cobre apps com login.

### Escopos

| Escopo | Libera | Quem recebe |
|---|---|---|
| `read` | `GET` | toda chave e todo ID token |
| `write` | `POST`, `PUT`, `DELETE` | chaves normais e ID tokens (não as somente leitura) |

## Limites

- **100 requisições por minuto por usuário** (todas as chaves da conta somam) e 300 por minuto por IP.
- Toda resposta traz `X-RateLimit-Limit` e `X-RateLimit-Remaining`. Passou do limite: `429` com `Retry-After` em segundos.
- **Plano Free**: até 100 lançamentos criados por mês (pela tela, pela API ou confirmando a caixa de entrada).
  Passou do limite: `402 Payment Required` com `limit` e `upgrade` no corpo. O plano Pro não tem limite.

## Recursos

Valores: `amount` é texto decimal (`"123.45"`) para nenhum cliente perder precisão com float, e
`amountCents` é o mesmo valor em centavos. `currency` é ISO 4217 (padrão: a moeda da conta).
Datas em ISO (`2026-10-26`).

| Método e caminho | O que faz |
|---|---|
| `GET /me` | Conta dona da credencial: id, e-mail, nome, moeda, plano e uso do mês. |
| `GET /categories?type=EXPENSE` | Lista categorias (filtro opcional por tipo). |
| `GET /categories/{id}` | Uma categoria. |
| `POST /categories` | Cria. Corpo: `{"name", "type": "EXPENSE"\|"INCOME", "color"?}`. `201` com `Location`. |
| `PUT /categories/{id}` | Atualiza. |
| `DELETE /categories/{id}` | Exclui (`409` se tiver lançamentos). |
| `GET /transactions?from&to&categoryId` | Lançamentos do período (padrão: mês atual). |
| `GET /transactions/{id}` | Um lançamento. |
| `POST /transactions` | Cria. Corpo: `{"type", "amount", "occurredOn", "categoryId", "currency"?, "description"?}`. |
| `PUT /transactions/{id}` | Atualiza (mesmo corpo). |
| `DELETE /transactions/{id}` | Exclui. `204`. |
| `GET /reports?from&to&categoryId` | Totais, despesas e receitas por categoria e evolução mensal. |
| `GET /inbox` | Pendentes da caixa de entrada. |
| `GET /inbox/{id}` | Um pendente. |
| `POST /inbox` | Cria um pendente (origem `API`). Categoria opcional. |
| `PUT /inbox/{id}` | Edita um pendente. |
| `POST /inbox/confirm` | `{"ids": [...]}`: vira lançamento o que tem categoria. Devolve `confirmed` e `skipped`. |
| `POST /inbox/discard` | `{"ids": [...]}`: descarta. Devolve `discarded`. |

Exemplo:

```bash
curl -X POST https://financas.exemplo.com/api/v1/transactions \
  -H "Authorization: Bearer scfk_SUA_CHAVE" -H "Content-Type: application/json" \
  -d '{"type": "EXPENSE", "amount": "42.90", "occurredOn": "2026-10-26",
       "categoryId": "6f1c...", "description": "Almoço"}'
```

```json
{"id": "0b9e...", "type": "EXPENSE", "amount": "42.90", "amountCents": 4290, "currency": "BRL",
 "occurredOn": "2026-10-26", "categoryId": "6f1c...", "description": "Almoço"}
```

## Erros

| Status | Quando |
|---|---|
| `400` | Corpo inválido. `errors` traz a mensagem por campo. |
| `401` | Sem `Authorization`, chave inválida ou revogada, token do Google inválido, conta desativada. |
| `402` | Limite mensal do plano Free atingido. |
| `403` | Chave somente leitura tentando escrever; origem CORS não permitida. |
| `404` | Não existe **ou é de outra conta** (as duas coisas são indistinguíveis de propósito). |
| `409` | Conflito (ex.: excluir categoria com lançamentos). |
| `429` | Limite de requisições. |

```json
{"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Dados inválidos",
 "errors": {"amount": "deve ser maior que 0"}}
```

## Isolamento entre contas

Cada requisição roda como o dono da credencial, com o Row-Level Security do Postgres ligado
(`user_id = app_current_user_id()`). Não há filtro por usuário no código para esquecer: uma conta
simplesmente não enxerga as linhas de outra, nem o administrador.

## CORS

Para chamar a API direto do navegador de outro domínio, liste a origem em `CORS_ALLOWED_ORIGINS`
(separadas por vírgula). Sem cookies (`Access-Control-Allow-Credentials` nunca é enviado): a chave vai
no header. Evite expor uma chave de escrita em código de front-end público; prefira uma somente leitura.

## Versionamento

Mudanças compatíveis (campos novos, endpoints novos) entram na v1. Mudança que quebra cliente vai para
`/api/v2`, com a v1 mantida por pelo menos 6 meses.

## API da sessão (uso interno)

As telas usam `/api/categories`, `/api/transactions`, `/api/reports` e `/api/inbox` com cookie de sessão
e CSRF. São os mesmos controllers, mas não fazem parte do contrato público.
