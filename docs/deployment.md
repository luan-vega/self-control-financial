# Deploy

Uma imagem Docker, um Postgres e HTTPS na frente. Os caminhos abaixo vão do mais simples
(Docker Compose num servidor) ao Kubernetes com deploy pelo GitHub Actions.

**Na AWS (free tier):** EC2 + RDS + nginx com Let's Encrypt, criados pelo Terraform e com deploy automático a
cada push na `main`. Passo a passo, custos e problemas comuns em [deploy-aws.md](deploy-aws.md).

## Requisitos

- **PostgreSQL 16+** (testado com 18). O usuário da aplicação **não pode ser superusuário nem ter
  `BYPASSRLS`**, senão o Row-Level Security não vale. A app se recusa a subir nesse caso.
  O script [`docker/db/init-app-user.sh`](../docker/db/init-app-user.sh) mostra os grants necessários.
- **HTTPS** terminando num proxy ou ingress que mande `X-Forwarded-Proto`. Com o perfil `prod`, a app
  redireciona HTTP para HTTPS (308) e recusa outros métodos em HTTP (403); só `/actuator/health` fica livre
  para as probes.
- 512 MB de memória por instância é suficiente para começar.

As migrations (Flyway) rodam sozinhas na subida. São só para frente: nada de editar uma migration publicada.

## Variáveis de ambiente

Segredos vêm **só** de variáveis de ambiente (ou do Secret do Kubernetes). Nada sensível fica nos
arquivos `application*.properties`.

| Variável | Para que serve | Padrão |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` em produção (HTTPS obrigatório, logs JSON, desligamento gracioso). Some `google` para o login com Google. | (nenhum) |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Conexão com o Postgres | `localhost`, `selfcontrol` |
| `DB_POOL_SIZE` | Conexões por instância (perfil `prod`) | `10` |
| `APP_PUBLIC_URL` | URL pública, usada nos links dos e-mails e no retorno do Stripe | URL da requisição |
| `ADMIN_EMAILS` | E-mails que viram administradores ao subir (separados por vírgula) | (nenhum) |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Login com Google (perfil `google`) | |
| `API_GOOGLE_CLIENT_IDS` | Client ids aceitos como ID token na API pública | (desligado) |
| `CORS_ALLOWED_ORIGINS` | Origens liberadas para chamar `/api/v1` do navegador | (nenhuma) |
| `RATE_LIMIT_API_PER_MINUTE` / `RATE_LIMIT_IP_PER_MINUTE` / `RATE_LIMIT_AUTH_PER_MINUTE` | Limites de requisições | `100` / `300` / `10` |
| `BILLING_PROVIDER` | `mock` (checkout de mentira, para testes) ou `stripe` | `mock` |
| `STRIPE_SECRET_KEY`, `STRIPE_PRICE_ID`, `STRIPE_WEBHOOK_SECRET` | Stripe (ver abaixo) | |
| `BILLING_PRO_PRICE_LABEL` | Preço mostrado na tela de planos | `R$ 9,90/mês` |
| `COOKIE_SECURE` | Cookie de sessão só em HTTPS (o perfil `prod` força `true`) | `true` |
| `REQUIRE_HTTPS` | Desliga o redirecionamento no perfil `prod` (só se o proxy já garante) | `true` |

## Docker Compose (um servidor)

```bash
docker compose --profile app up -d --build
```

Isso sobe o Postgres e a app com o perfil `dev`. Para produção num servidor, crie um `.env` ao lado do
`compose.yaml` com `SPRING_PROFILES_ACTIVE=prod`, `APP_PUBLIC_URL`, os segredos do Stripe e senhas de
banco fortes, e coloque um proxy com TLS na frente (Caddy, Traefik ou nginx + Let's Encrypt).

A imagem tem `HEALTHCHECK` próprio (`/actuator/health/liveness`), então `docker ps` mostra `healthy`.

## Imagem

O [`Dockerfile`](../Dockerfile) tem três etapas: build com Maven (compila também o Tailwind), extração
do jar em camadas (dependências, loader, snapshots, aplicação) e uma imagem final só com o JRE 25, rodando
como usuário sem privilégios (uid 10001). Mudar só o código reconstrói só a última camada.

```bash
docker build -t self-control-financial .
docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=prod -e DB_URL=... self-control-financial
```

A imagem roda com sistema de arquivos somente leitura (só `/tmp` precisa ser gravável).

## Kubernetes

Manifests em [`deploy/k8s`](../deploy/k8s), aplicados com Kustomize:

| Arquivo | O quê |
|---|---|
| `namespace.yaml` | Namespace `self-control-financial` |
| `configmap.yaml` | Configuração não sensível (perfil, URL pública, provedor de cobrança) |
| `secret.example.yaml` | Só documenta as chaves do Secret. **Não aplique com valores reais.** |
| `deployment.yaml` | 1 réplica, sem root, rootfs somente leitura, probes de startup/liveness/readiness, `preStop` para desligar sem derrubar requisições |
| `service.yaml` | Service na porta 80 |
| `ingress.yaml` | Ingress nginx com TLS via cert-manager e limite por IP na borda |
| `pdb.yaml` | PodDisruptionBudget |

Primeiro deploy:

```bash
# 1. Edite o host em ingress.yaml e APP_PUBLIC_URL em configmap.yaml
# 2. Crie o Secret direto no cluster (os comandos estão em secret.example.yaml)
kubectl create namespace self-control-financial
kubectl -n self-control-financial create secret generic self-control-financial --from-literal=DB_URL=... ...
# 3. Aplique
kubectl apply -k deploy/k8s
kubectl -n self-control-financial rollout status deployment/self-control-financial
```

O Postgres fica fora desses manifests: use um gerenciado (RDS, Cloud SQL, Neon, Supabase) ou um operador
(CloudNativePG). Faça backup diário com retenção e teste a restauração.

### Probes

- `/actuator/health/liveness`: o processo está vivo.
- `/actuator/health/readiness`: pronto para tráfego (inclui o banco). Fora do ar, o pod sai do Service.

## CI/CD (GitHub Actions)

| Workflow | Quando | O quê |
|---|---|---|
| [`ci.yml`](../.github/workflows/ci.yml) | PR e push na `main` | `./mvnw verify` (testes com Testcontainers + cobertura) e build da imagem, sem publicar |
| [`release.yml`](../.github/workflows/release.yml) | Push na `main` e tags `v*` | Testa de novo e publica `ghcr.io/luan-vega/self-control-financial` com as tags `<sha>`, `latest` e `<versão>` |
| [`deploy.yml`](../.github/workflows/deploy.yml) | Manual (Actions → Deploy → Run workflow) | Aplica `deploy/k8s` com a imagem escolhida e espera o rollout |
| [`deploy-aws.yml`](../.github/workflows/deploy-aws.yml) | Depois de cada Release da `main`, ou manual | Atualiza a EC2 via SSM e volta para a versão anterior se a nova não ficar saudável ([deploy-aws.md](deploy-aws.md)) |

Para o deploy funcionar, crie o environment **production** no repositório (Settings → Environments),
com o secret `KUBE_CONFIG` (kubeconfig em base64, de uma service account com acesso só ao namespace).
Exigir aprovação no environment transforma o deploy num passo com revisão.

Rollback: rode o Deploy de novo com a tag (sha) da versão anterior, ou
`kubectl -n self-control-financial rollout undo deployment/self-control-financial`. Como as migrations
só vão para frente, toda migration nova precisa ser compatível com a versão anterior da app
(adicionar coluna nula, não renomear nem apagar no mesmo deploy).

## Stripe

1. No painel do Stripe, crie o produto "Pro" com um preço mensal recorrente e copie o `price_...` para `STRIPE_PRICE_ID`.
2. Copie a chave secreta (`sk_live_...` ou `sk_test_...`) para `STRIPE_SECRET_KEY`.
3. Em Developers → Webhooks, adicione o endpoint `https://<host>/api/billing/webhook` com os eventos
   `checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`,
   `customer.subscription.deleted`, `invoice.payment_failed` e `invoice.paid`. Copie o signing secret
   (`whsec_...`) para `STRIPE_WEBHOOK_SECRET`.
4. Ative o Customer Portal (Settings → Billing → Customer portal), usado pelo botão "Gerenciar assinatura".
5. `BILLING_PROVIDER=stripe`.

O webhook confere a assinatura (HMAC-SHA256, tolerância de 5 minutos) e ignora eventos repetidos
(tabela `billing_event`). Sem `STRIPE_WEBHOOK_SECRET`, responde 503 e o Stripe tenta de novo depois.
Para testar localmente: `stripe listen --forward-to localhost:8080/api/billing/webhook`.

## E-mail

Hoje os e-mails (confirmação de cadastro e troca de senha) vão para o **log** (`LogMailer`, logger
`com.selfcontrol.app.account.LogMailer`), com o link completo. Antes de abrir o cadastro ao público,
implemente a interface `Mailer` com um provedor (SES, Postmark, Resend) e registre como bean.

## Logs, auditoria e observabilidade

- Perfil `prod`: logs em JSON no formato ECS, prontos para Loki, Elastic, Datadog ou CloudWatch.
- Toda requisição recebe `X-Request-Id` (ou reaproveita o que o proxy mandou), que vai no log de acesso
  (`http.access`) e em todas as linhas daquela requisição, junto com o id do usuário.
- A trilha de auditoria (`audit_event`) guarda login, cadastro, mudanças de conta, chaves de API,
  lançamentos e cobrança, com IP, user agent e canal (`WEB`, `API`, `WEBHOOK`, `SYSTEM`). É só inserção:
  um trigger no banco recusa `UPDATE` e `DELETE`. Os administradores veem em `/admin/audit`.

## Escalando para mais de uma réplica

Rate limit e cache de relatórios ficam **em memória**, por instância. Com 1 réplica isso é exato e
dispensa Redis. Com N réplicas:

- O limite efetivo vira até N × 100 req/min por usuário. Ou aceite (o limite por IP na borda segura o
  abuso), ou troque o `RateLimiter` por um com Redis (Bucket4j + Lettuce).
- O cache de relatórios invalida só na instância que recebeu a escrita; as outras podem mostrar números
  até 10 minutos velhos. Diminua `app.reports.cache.ttl` ou desligue (`app.reports.cache.enabled=false`).
- Sessões já ficam no Postgres (Spring Session JDBC), então não precisa de sticky session.

## Checklist antes de abrir para o público

- [ ] `SPRING_PROFILES_ACTIVE=prod` e HTTPS com certificado válido
- [ ] Usuário do banco sem `BYPASSRLS`, senha forte, backup testado
- [ ] `ADMIN_EMAILS` com o seu e-mail
- [ ] Stripe em modo live, webhook configurado e testado
- [ ] `Mailer` real no lugar do `LogMailer`
- [ ] Termos de Uso e Política de Privacidade revisados por um advogado (os textos atuais são um ponto de partida)
- [ ] Alertas nos logs para `http.status >= 500` e para `auth.login_failed` em excesso
