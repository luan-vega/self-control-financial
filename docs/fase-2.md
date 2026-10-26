# Fase 2: automação

Importação de extratos (OFX/CSV), caixa de entrada e captura rápida pelos Atalhos do iOS.
Nada do que chega por esses caminhos entra direto nas contas: tudo passa pela **caixa de entrada**,
onde você revisa, ajusta a categoria e confirma.

```
arquivo OFX/CSV ──► preview (import_batch + import_row) ──confirmar──► caixa de entrada (inbox_item)
Atalho do iPhone ──► POST /api/transactions/webhook ─────────────────►        │
                                                                     confirmar │ descartar
                                                                               ▼
                                                                  financial_transaction
```

## Telas

| Caminho | O que faz |
|---|---|
| `/inbox` | Caixa de entrada: pendentes com categoria editável na própria linha, edição completa, marcar todos, confirmar e descartar em lote. Itens iguais a um lançamento já confirmado aparecem com o selo "Possível duplicata". |
| `/imports/new` | Upload: arraste o arquivo ou toque para escolher. O preview aparece na mesma tela (HTMX). |
| `/imports/{id}` | Preview (com as linhas marcadas para importar) ou log de um import já fechado. |
| `/imports` | Histórico: arquivo, data, linhas novas/duplicadas/com erro e quantas foram para a caixa de entrada. |
| `/settings/shortcut` | Token do webhook (gerar, trocar, revogar), URL, exemplo de corpo e ids das categorias. |

O início mostra um ícone de caixa de entrada com o número de pendentes; o extrato tem atalhos para Importar e Caixa de entrada.

## Formatos aceitos

Arquivo de até **5 MB** e **5.000 lançamentos**. Texto em UTF-8 ou Windows-1252 (o que muitos bancos e o Excel exportam);
a codificação é detectada sozinha. O formato é detectado pela extensão (`.ofx`/`.qfx`, `.csv`/`.txt`) e, sem extensão, pelo conteúdo.

### OFX

- OFX 1.x (SGML, o "extrato para Money" dos bancos brasileiros) e OFX 2.x (XML).
- Cada `<STMTTRN>` vira uma linha. `TRNAMT` negativo é despesa, positivo é receita. Aceita vírgula decimal (`-12,34`).
- Data: os 8 primeiros dígitos de `DTPOSTED` (hora e fuso são ignorados).
- Descrição: `NAME` e `MEMO` juntos (`PIX ENVIADO - Fulano`); só um deles quando o outro está vazio ou é igual.
- Moeda: `CURDEF`; sem ela, a moeda padrão da conta.

### CSV

Sem cabeçalho, a ordem é:

```
data, descrição, valor, categoria, tipo
```

`categoria` e `tipo` são opcionais. Com cabeçalho, as colunas podem vir em qualquer ordem e são reconhecidas pelo nome
(sem acento nem caixa):

| Coluna | Nomes aceitos |
|---|---|
| data | `data…`, `date`, `dt` |
| descrição | `descrição…`, `histórico…`, `title`, `memo`, `estabelecimento…`, `lançamento…` |
| valor | `valor…` (ex.: `Valor (R$)`), `amount…`, `value`, `quantia` |
| categoria | `categ…` |
| tipo | `tipo`, `type`, `natureza` |
| identificador | `identificador`, `id`, `fitid` (guardado no log) |

- **Separador**: `;`, `,` ou tab, detectado na primeira linha. Campos com o separador dentro vão entre aspas (`"Aluguel; outubro"`).
- **Datas**: `05/10/2026`, `5/10/2026`, `05-10-2026`, `05.10.2026`, `05/10/26`, `2026-10-05`, `20261005`. Hora depois da data é ignorada.
- **Valores**: `1.234,56`, `1234,56`, `1234.56`, `1,234.56`, `R$ 12,34`, `-12,34`, `(12,34)`, `12,34-`, `12,34 D` (débito), `12,34 C` (crédito).
  Casas decimais além das da moeda viram erro na linha (`1,234` em reais).
- **Despesa ou receita**:
  1. coluna `tipo` (`receita`/`despesa`, `income`/`expense`, `crédito`/`débito`, `C`/`D`, `entrada`/`saída`);
  2. se algum valor do arquivo é negativo, o arquivo usa sinal: negativo é despesa, positivo é receita (extrato de conta);
  3. se todos são positivos (planilha de gastos), vale o tipo da categoria encontrada; sem categoria, despesa.

Exemplos que funcionam:

```csv
Data;Descrição;Valor;Categoria
05/10/2026;Supermercado;643,90;Mercado
06/10/2026;"Aluguel; outubro";2.200,00;Moradia
```

```csv
Data,Valor,Identificador,Descrição
01/10/2026,-45.90,abc-1,Transferência enviada pelo Pix
02/10/2026,1200.00,abc-2,Transferência recebida
```

```csv
2026-10-03,iFood,89.90,Restaurantes
2026-10-04,Netflix,55.90,Lazer
```

## Regras do import

- **Validação por linha**: data válida (entre 1900 e 2999), valor diferente de zero e com as casas da moeda. Linha ruim vira
  "Erro" com o motivo e não pode ser importada; o resto do arquivo segue.
- **Categoria automática** (sem IA): pelo nome da coluna categoria, comparado sem acento nem caixa com as suas categorias;
  se não houver, pela categoria do lançamento mais recente com a mesma descrição; senão fica "Sem categoria" e você escolhe na caixa.
  Categoria inexistente não é criada: a linha mostra "Categoria X não encontrada".
- **Duplicatas**: mesma data + valor (com tipo e moeda) + descrição normalizada (sem acento, caixa e espaços extras), comparando
  com os lançamentos confirmados e com tudo que já passou pela caixa de entrada, inclusive o que foi descartado. A comparação
  conta ocorrências: se o extrato tem dois cafés iguais no mesmo dia e só um já existe, um é duplicata e o outro é novo.
  Duplicatas ficam desmarcadas no preview; marque se forem lançamentos diferentes.
- **Tudo ou nada**: confirmar copia as linhas marcadas para a caixa de entrada numa única transação. Se alguma linha pedida tem
  erro ou não é daquele import, nada é importado. O import é travado antes da cópia, então um clique duplo não importa duas vezes.
- **Síncrono**: com os limites acima, ler e gravar leva menos de um segundo, então não há job assíncrono. Se os limites crescerem,
  `ImportClient.preview` é o ponto para virar assíncrono.

## Caixa de entrada

- Confirmar em lote transforma cada item em `financial_transaction`. Itens sem categoria (ou com categoria do tipo errado)
  são pulados e continuam na caixa, com aviso.
- Descartar tira da caixa, mas o item fica guardado como `DISCARDED` para que reimportar um período sobreposto não o traga de volta.
- Editar permite trocar tipo, valor, data, descrição e categoria.

## Webhook dos Atalhos do iOS

`POST /api/transactions/webhook`, sem sessão e sem CSRF, autenticado por um **token secreto por usuário**:

```
Authorization: Bearer scf_…
```

(ou o header `X-Webhook-Token: scf_…`). O token é gerado em `/settings/shortcut`, aparece uma única vez e o banco guarda só o
SHA-256. Gerar outro invalida o anterior; revogar desliga o webhook. O token não serve para nenhuma outra rota.

Corpo (JSON). Todos os campos são texto para aceitar o que o Atalhos mandar; números também funcionam:

| Campo | Obrigatório | Formato |
|---|---|---|
| `amount` | sim | `"12,50"`, `"R$ 1.234,56"`, `12.5`. O sinal é ignorado. |
| `description` | não | até 200 caracteres (ex.: o comerciante) |
| `date` | não | `"2026-10-26"`, `"2026-10-26T14:05:00-03:00"` ou `"26/10/2026"`. Sem data, hoje. |
| `type` | não | `EXPENSE` (padrão) ou `INCOME` (também `despesa`/`receita`) |
| `category_id` | não | id de uma categoria sua (lista em `/settings/shortcut`). Sem ele, categoria automática pela descrição. |
| `currency` | não | código ISO (`BRL`, `USD`). Sem ele, a moeda da conta. |

Respostas:

- `201` com o item pendente: `{"id": "…", "status": "PENDING", "type": "EXPENSE", "amount": "12.50", "amountCents": 1250, "currency": "BRL", "occurredOn": "2026-10-26", "description": "Padaria", "categoryId": "…", "categoryName": "Mercado"}`
- `400` com `errors: {campo: mensagem}` (nomes de campo do webhook: `amount`, `date`, `type`, `category_id`…)
- `401` sem token, token errado ou revogado.

### Configurando o Atalho (automação da Carteira)

Requer iOS 17 ou mais novo e cartões na Carteira.

1. No app que você usa (desktop ou iPhone), abra **Atalho do iPhone** (`/settings/shortcut`), toque em **Gerar token** e copie.
2. No iPhone, abra **Atalhos → Automação → Nova Automação → Transação**.
3. Escolha os cartões (e, se quiser, categorias ou comerciantes) e marque **Executar Imediatamente**. Avançar.
4. **Nova Automação em Branco → Adicionar Ação → Obter Conteúdo do URL**.
5. URL: a mostrada na tela (ex.: `https://seu-dominio/api/transactions/webhook`). Toque na seta e configure:
   - **Método**: POST
   - **Cabeçalhos**: `Authorization` = `Bearer ` + o token (com espaço depois de Bearer)
   - **Corpo da Solicitação**: JSON, com os campos
     - `amount` (Texto) = variável **Valor** da Transação
     - `description` (Texto) = variável **Comerciante**
     - `type` (Texto) = `EXPENSE`
6. Concluído. A próxima compra com o cartão aparece na caixa de entrada.

Para lançar à mão (dinheiro, Pix), crie um atalho comum com **Pedir Entrada** (número) para o valor e outra para a descrição,
seguido do mesmo **Obter Conteúdo do URL**. Dá para colocá-lo na tela de início ou chamar pela Siri.

> O webhook precisa de uma URL acessível pelo iPhone com HTTPS (o deploy). Em casa, na mesma rede, `http://IP-da-máquina:8080`
> funciona para testar, mas a automação da Carteira roda em segundo plano e só funciona de verdade com a app publicada.

Teste pelo terminal:

```bash
curl -X POST https://seu-dominio/api/transactions/webhook \
  -H "Authorization: Bearer scf_SEU_TOKEN" -H "Content-Type: application/json" \
  -d '{"description": "Padaria", "amount": "12,50"}'
```

## Banco (`V5__imports_inbox_webhook.sql`)

| Tabela | RLS | O que guarda |
|---|---|---|
| `import_batch` | sim | Um arquivo: origem (OFX/CSV), nome, status (`PREVIEW`, `IMPORTED`, `CANCELLED`), contadores, quando foi criado e fechado. |
| `import_row` | sim | Cada linha interpretada e o resultado (`NEW`, `DUPLICATE`, `ERROR`) com a mensagem. É o log do import. |
| `inbox_item` | sim | Pendentes (`PENDING`) e descartados (`DISCARDED`), com origem (OFX, CSV, WEBHOOK). Confirmados viram `financial_transaction` e saem daqui. |
| `webhook_token` | não | Um token por usuário (só o hash e os 4 últimos caracteres). Sem RLS como `app_user`: é lida para descobrir **quem** chama, antes de existir usuário no contexto. As telas filtram por `user_id = app_current_user_id()`. |

As FKs de categoria e de import são compostas com `user_id` (como em `financial_transaction`), então ninguém aponta para a
categoria ou o import de outro usuário mesmo sabendo o id.

O webhook autentica o dono do token como principal da requisição, então o `RlsTransactionManager` liga o RLS exatamente como
nas telas.

## Solução de problemas

| Sintoma | Causa provável e o que fazer |
|---|---|
| "O arquivo não parece ser um OFX" | O banco exportou outro formato com extensão `.ofx` (às vezes PDF ou HTML). Baixe de novo escolhendo OFX/Money, ou use CSV. |
| "O cabeçalho precisa ter as colunas de data e valor" | O CSV tem cabeçalho, mas com nomes que não reconhecemos. Renomeie as colunas para `data`, `descrição`, `valor` ou remova o cabeçalho e use a ordem padrão. |
| Acentos estranhos (`SÃ£o`) | Raro, porque a codificação é detectada. Se acontecer, salve o CSV como "CSV UTF-8" no Excel/Numbers. |
| Todas as linhas com "Data inválida" | Formato de data fora da lista (ex.: `Out 05, 2026`). Converta a coluna para `dd/mm/aaaa` na planilha. |
| "Valor com mais de 2 casas decimais" | Valor como `1,234` foi lido como 1,234 reais. Use `1.234` só com milhar completo (`1.234,00`) ou sem separador (`1234`). |
| Despesas aparecendo como receitas | Fatura de cartão que usa positivo para compra e negativo para pagamento: o arquivo tem negativos, então vale "negativo = despesa". Edite o tipo na caixa de entrada, ou adicione uma coluna `tipo`. |
| Tudo marcado como "Duplicada" | O mesmo período já foi importado (ou descartado). É o esperado; marque à mão o que for realmente novo. |
| "Arquivo grande demais" / "mais de 5000 lançamentos" | Exporte períodos menores (um mês por vez). |
| Webhook responde `401` | Token errado, revogado ou trocado. Gere outro em Atalho do iPhone e atualize o header `Authorization` (lembre do `Bearer ` com espaço). |
| Webhook responde `400` com `errors.amount` | O Atalho mandou o valor vazio ou como texto não numérico. Confira se a variável **Valor** está no campo `amount`. |
| A automação não roda sozinha | No iPhone, a automação precisa estar com **Executar Imediatamente** e a URL precisa ser HTTPS pública. Rode o atalho à mão uma vez para ver o erro. |
| Lançamento ficou "Sem categoria" | Não havia categoria com aquele nome nem lançamento anterior com a mesma descrição. Escolha na caixa: da próxima vez a mesma descrição já vem categorizada. |
