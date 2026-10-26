# Deploy na AWS (free tier)

Uma EC2 com a app em Docker e nginx na frente, um RDS PostgreSQL e deploy automático pelo GitHub
Actions a cada push na `main`. Tudo cabe no free tier da AWS e cresce sem refazer nada: o próximo
passo natural é um load balancer com certificado do ACM e uma segunda instância.

Requisitos gerais da app (RLS, HTTPS, variáveis) estão em [deployment.md](deployment.md).

```
                          ┌───────────────────────── EC2 t2.micro (Ubuntu 24.04) ─────────────────────────┐
 navegador / iPhone ──443─▶ nginx (Let's Encrypt, HTTP→HTTPS, limite por IP) ──▶ container da app :8080   │──5432 (TLS)──▶ RDS PostgreSQL
                          │ logs em /var/log/self-control           ▲                                     │                db.t3.micro, sem IP público
                          └─────────────────────────────────────────┼─────────────────────────────────────┘
 GitHub Actions ──OIDC──▶ IAM ──SSM Run Command──────────────────────┘ (sem SSH, sem chave da AWS no GitHub)
 imagem: ghcr.io/luan-vega/self-control-financial:<sha> (publicada pelo release.yml)
```

## O que fica onde

| Caminho | O quê |
|---|---|
| [`deploy/aws/terraform/`](../deploy/aws/terraform) | EC2, IP elástico, RDS, security groups, IAM (EC2 + deploy via OIDC), alarmes do CloudWatch, orçamento, registro no Route 53 (opcional) |
| [`deploy/aws/server/bootstrap.sh`](../deploy/aws/server/bootstrap.sh) | Primeiro boot da EC2: swap de 2 GB, Docker, nginx, certbot, atualizações de segurança automáticas |
| [`deploy/aws/server/docker-compose.yml`](../deploy/aws/server/docker-compose.yml) | A app, presa a `127.0.0.1:8080`, com limite de memória, rootfs somente leitura e logs em arquivo |
| [`deploy/aws/server/env.example`](../deploy/aws/server/env.example) | Modelo do `/opt/self-control/.env` (segredos) |
| [`deploy/aws/server/nginx/`](../deploy/aws/server/nginx) | nginx com TLS, redirecionamento HTTP→HTTPS, limite por IP e página de manutenção |
| [`deploy/aws/server/scripts/`](../deploy/aws/server/scripts) | `install.sh` (copia os arquivos), `create-db-user.sh`, `setup-https.sh`, `deploy.sh` (com rollback automático) |
| [`.github/workflows/deploy-aws.yml`](../.github/workflows/deploy-aws.yml) | Deploy automático depois do Release, ou manual com uma tag |

No servidor tudo vive em `/opt/self-control` (arquivos e `.env`) e `/var/log/self-control` (logs).

## Antes de começar

- **Conta da AWS.** Desde 15/07/2025 a AWS tem dois regimes:
  - **Free tier clássico** (contas criadas antes): 12 meses de 750 h/mês de `t2.micro` (ou `t3.micro`
    onde não há t2) e de `db.t3.micro`/`db.t4g.micro`, 30 GB de EBS, 20 GB de RDS e 20 GB de backup.
    Use os padrões do Terraform.
  - **Plano Free** (contas novas): créditos de US$ 100 (até US$ 200 completando atividades) por até
    6 meses. Use `instance_type = "t3.micro"` e `db_backup_retention_days = 1` (o plano Free recusa mais
    que 1 dia). Antes de acabar os créditos ou o prazo, mude a conta para o plano pago, senão ela é
    fechada.
- **Ferramentas na sua máquina:** [Terraform 1.6+](https://developer.hashicorp.com/terraform/install),
  [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) logado
  (`aws configure` ou `aws sso login`) com um usuário administrador (não use a conta root).
- **Domínio** (recomendado). O perfil `prod` exige HTTPS, e o Let's Encrypt precisa de um nome. Sem
  domínio próprio, veja [Sem domínio](#sem-domínio).
- A imagem precisa existir no GHCR: o [`release.yml`](../.github/workflows/release.yml) publica a cada push
  na `main`.

## Passo a passo

### 1. Criar a infraestrutura (Terraform)

```bash
cd deploy/aws/terraform
cp terraform.tfvars.example terraform.tfvars   # edite: região, seu IP, chave SSH, domínio, e-mail
terraform init
terraform plan
terraform apply
```

Leva uns 10 minutos (o RDS é o mais lento). No fim aparecem o IP elástico, o id da instância, o papel de
deploy e os próximos passos (`terraform output next_steps`).

- `terraform.tfstate` guarda a senha mestre do RDS: **não faça commit** (o `.gitignore` já ignora) e guarde
  uma cópia segura, ou use o backend S3 comentado em `versions.tf`.
- O banco nasce com `deletion_protection = true`: nem `terraform destroy` apaga sem antes desligar isso.
- Confirme o e-mail "AWS Notification - Subscription Confirmation" para receber os alarmes.

### 2. Apontar o DNS

Crie um registro **A** do seu domínio (ex.: `financas.seudominio.com.br`) para o `public_ip`. Se você
passou `route53_zone_id`, o Terraform já criou. Confira: `dig +short financas.seudominio.com.br`.

### 3. Configurar o GitHub

Em **Settings → Environments → production** (crie se não existir) adicione as **variáveis** (não são
segredos, nenhuma chave da AWS vai para o GitHub):

| Variável | Valor |
|---|---|
| `AWS_REGION` | a região, ex.: `sa-east-1` |
| `AWS_DEPLOY_ROLE_ARN` | `terraform output -raw github_deploy_role_arn` |
| `EC2_INSTANCE_ID` | `terraform output -raw instance_id` |
| `APP_PUBLIC_URL` | `https://financas.seudominio.com.br` (opcional: teste final pelo HTTPS) |

Enquanto essas variáveis não existem, o workflow só registra um aviso e não faz nada.

### 4. Copiar os arquivos para o servidor

```bash
# da raiz do repositório
scp -r deploy/aws/server ubuntu@<public_ip>:/tmp/self-control
ssh ubuntu@<public_ip>
cloud-init status --wait          # espera o bootstrap terminar (Docker, nginx, swap)
sudo bash /tmp/self-control/scripts/install.sh
```

Sem SSH (porta 22 fechada)? Use `aws ssm start-session --target <instance_id>` (precisa do
[plugin do Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html))
e copie os arquivos com `git clone` do repositório, ou rode o workflow **Deploy AWS** uma vez: ele instala os
arquivos e para avisando que falta o `.env`.

### 5. Banco e variáveis de ambiente

Ainda no servidor:

```bash
sudo cp /opt/self-control/env.example /opt/self-control/.env
sudo chmod 600 /opt/self-control/.env
sudo nano /opt/self-control/.env    # APP_PUBLIC_URL, ADMIN_EMAILS, Stripe, Google...

# Cria o banco "selfcontrol" e o usuário da app (sem BYPASSRLS) e grava DB_URL/DB_USER/DB_PASSWORD no .env.
# Pede a senha mestre: rode "terraform output -raw db_master_password" na sua máquina.
sudo /opt/self-control/scripts/create-db-user.sh <db_endpoint>
```

A senha da app é gerada no servidor e fica só no `.env`. O usuário `postgres` do RDS não é usado pela app
(ela se recusa a subir com um usuário que ignora o Row-Level Security).

### 6. Acesso à imagem no GHCR

Se o pacote `self-control-financial` no GitHub for **público** (Package settings → Change visibility),
pule este passo. Se for privado, crie um token clássico só com `read:packages` e faça login uma vez:

```bash
echo <token> | sudo docker login ghcr.io -u luan-vega --password-stdin
```

O login fica em `/root/.docker/config.json`, usado pelo deploy (que roda como root).

### 7. HTTPS

```bash
sudo /opt/self-control/scripts/setup-https.sh voce@exemplo.com
```

O script confere se o domínio já aponta para este servidor, pede o certificado ao Let's Encrypt, instala
o nginx com HTTPS e redirecionamento de HTTP, e testa a renovação (automática, pelo timer do certbot, que
recarrega o nginx).

**Por que não ACM?** Certificados públicos do ACM são grátis, mas só funcionam em load balancer, CloudFront
e API Gateway: não dá para instalar no nginx de uma EC2 (a versão exportável é paga). O Let's Encrypt
resolve de graça. Quando entrar um load balancer, o TLS passa para ele com ACM e o nginx vira opcional.

### 8. Primeiro deploy

No GitHub: **Actions → Deploy AWS → Run workflow** (tag vazia = último commit da `main`). Ou no servidor:

```bash
sudo /opt/self-control/scripts/deploy.sh latest
```

Abra `https://financas.seudominio.com.br`, crie a conta com o e-mail que está em `ADMIN_EMAILS` (o link de
confirmação aparece no log enquanto não houver `Mailer` real, ver [deployment.md](deployment.md#e-mail)):

```bash
sudo grep -h 'E-mail (simulado)' /var/log/self-control/app/app.log | tail -n 1 | jq -r .message
```

No iPhone, abra no Safari → Compartilhar → **Adicionar à Tela de Início**.

## Dia a dia

### Deploy automático

Push na `main` → CI → **Release** publica `ghcr.io/...:<sha>` → **Deploy AWS** manda pelo SSM:

1. os arquivos de `deploy/aws/server` daquele commit (`install.sh`, que nunca mexe no `.env`);
2. `deploy.sh <sha>`: baixa a imagem, troca o container e espera `/actuator/health/readiness` (que inclui o
   banco). Se não ficar saudável em 5 minutos, volta sozinho para a versão anterior e o workflow falha.

Com uma instância, a app fica fora uns 30 a 60 segundos por deploy; o nginx mostra uma página de
"Atualizando" que recarrega sozinha. As migrations (Flyway) rodam na subida, então valem as regras de
[deployment.md](deployment.md#cicd-github-actions): só para frente e compatíveis com a versão anterior.

Para exigir aprovação antes de cada deploy, ligue **Required reviewers** no environment `production`.

### Rollback

**Actions → Deploy AWS → Run workflow** com a tag (sha) da versão anterior, ou no servidor
`sudo /opt/self-control/scripts/deploy.sh <sha>`. A versão atual está em `IMAGE_TAG` no `.env` e o
histórico em `/var/log/self-control/deploy.log`.

### Logs

| Onde | O quê |
|---|---|
| `/var/log/self-control/app/app.log` | App em JSON (ECS). O Spring Boot gira: 10 MB por arquivo, 7 arquivos |
| `/var/log/self-control/nginx/access.log` | Acessos em JSON, com `request_id` (o mesmo `X-Request-Id` que vai para o log da app) |
| `/var/log/self-control/nginx/error.log` | Erros do nginx |
| `/var/log/self-control/deploy.log` | Cada deploy e rollback |
| `docker logs self-control-app` | Saída do container (últimos 30 MB) |

```bash
sudo tail -f /var/log/self-control/app/app.log | jq -r '"\(.["@timestamp"]) \(.["log.level"]) \(.message)"'
sudo jq -r 'select(.status >= 500)' /var/log/self-control/nginx/access.log
```

### Health check

- `https://<domínio>/actuator/health`: público, responde `{"status":"UP"}`.
- `/actuator/health/readiness`: inclui o banco; usado pelo deploy.
- `/actuator/health/liveness`: usado pelo `HEALTHCHECK` do container (`docker ps` mostra `healthy`).

Para ser avisado quando o site cair, cadastre `https://<domínio>/actuator/health` num monitor externo
grátis (UptimeRobot, Better Stack). O health check do Route 53 também serve, mas custa US$ 0,50/mês.

### Monitoramento (CloudWatch)

As métricas básicas da EC2 (a cada 5 min) e do RDS (a cada 1 min) já vêm ligadas e não custam nada. O
Terraform cria 7 alarmes (o free tier cobre 10), que mandam e-mail se `alert_email` estiver definido:

| Alarme | Quando | Ação |
|---|---|---|
| `ec2-system-check` | Falha no host da AWS | Recupera a instância em outro host (mesmo IP e disco) |
| `ec2-instance-check` | SO travado | Reinicia a instância |
| `ec2-cpu` / `ec2-cpu-credits` | CPU > 80% / créditos de CPU acabando | E-mail |
| `rds-cpu` / `rds-storage` / `rds-memory` | CPU > 80% / < 2 GB livres / < 64 MB de RAM | E-mail |

E um **orçamento** de US$ 5/mês (`monthly_budget_usd`) avisa em 80% do gasto real e quando a previsão passar
de 100%. É a melhor proteção contra uma conta surpresa quando o free tier acabar.

### Backups

- **Automáticos do RDS:** diários às 03:00 (Brasília), guardados por `db_backup_retention_days` dias, com
  restauração para qualquer segundo dentro desse período (point-in-time).
- **Snapshot manual** antes de mudanças grandes (fica até você apagar; 20 GB de backup são grátis):
  `aws rds create-db-snapshot --db-instance-identifier self-control-db --db-snapshot-identifier antes-de-x`
- **Restaurar:** o RDS sempre restaura numa instância nova. Restaure
  (`aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier self-control-db --target-db-instance-identifier self-control-db-restore --restore-time 2026-10-26T12:00:00Z --db-subnet-group-name self-control-db --vpc-security-group-ids <sg do db>`),
  troque o host no `DB_URL` do `.env` e rode `deploy.sh` com a tag atual. Teste isso uma vez antes de precisar.
- Cópia fora da AWS, de vez em quando:
  `sudo bash -c 'set -a; . /opt/self-control/.env; h=${DB_URL#*//}; PGPASSWORD=$DB_PASSWORD pg_dump -h ${h%%:*} -U $DB_USER -Fc selfcontrol' > backup.dump`

### Conectar no banco

O RDS não tem IP público. Pelo servidor: `psql "host=<db_endpoint> user=selfcontrol dbname=selfcontrol sslmode=require"`.
Da sua máquina, por um túnel SSH: `ssh -L 5433:<db_endpoint>:5432 ubuntu@<public_ip>` e conecte em `localhost:5433`.

## Sem domínio

O perfil `prod` recusa HTTP, então é preciso um nome com certificado. Opções grátis:

- **DuckDNS** (`seunome.duckdns.org`): crie o subdomínio, aponte para o IP elástico e use em `APP_PUBLIC_URL`.
- **sslip.io**: `https://<ip-com-tracos>.sslip.io` (ex.: `54-233-1-2.sslip.io`) resolve para o IP sem cadastro.
  Funciona com o Let's Encrypt, mas o domínio é compartilhado e pode bater no limite de certificados.

Um domínio `.com.br` custa R$ 40/ano no Registro.br e evita trocar a URL depois (o PWA instalado no
iPhone fica preso à URL).

## Custos

Valores aproximados em USD por mês, sem impostos, para uso pequeno. Confira na
[calculadora da AWS](https://calculator.aws/) antes de decidir.

| Item | Free tier | Depois: us-east-1 | Depois: sa-east-1 |
|---|---|---|---|
| EC2 t3.micro (t2.micro é um pouco mais cara) | 0 | ~7,60 | ~12 |
| EBS 20 GB gp3 | 0 | ~1,60 | ~3 |
| IP público (IPv4) | 0 (750 h/mês) | ~3,65 | ~3,65 |
| RDS db.t3.micro | 0 | ~13 | ~25 |
| RDS 20 GB + backup até 20 GB | 0 | ~2,30 | ~4,50 |
| CloudWatch (alarmes, métricas básicas) | 0 | 0 | 0 |
| Transferência de dados (até 100 GB/mês) | 0 | 0 | 0 |
| Route 53 (opcional) | 0,50 | 0,50 | 0,50 |
| **Total** | **~0** | **~28** | **~48** |

Para baixar o custo depois do primeiro ano, do mais simples ao mais trabalhoso:

1. **Reserved Instances / Savings Plans de 1 ano:** cerca de 30 a 40% a menos na EC2 e no RDS, sem mudar nada.
2. **us-east-1:** quase metade do preço de São Paulo; a latência extra (~120 ms) quase não se nota num app de
   finanças pessoais.
3. **Postgres no próprio servidor** (o `compose.yaml` da raiz já sabe subir): tira os ~15 a 30 do RDS, mas
   backup, atualização e restauração passam a ser com você. Com uma `t3.small` (2 GB) para caber os dois.
4. **Graviton (`t4g`)**: ~20% mais barata, mas exige imagem multi-arquitetura (`platforms: linux/amd64,linux/arm64`
   no `release.yml`).

Fora do free tier, coisas que custam e que este setup evita de propósito: NAT Gateway (~US$ 32/mês),
load balancer (~US$ 16/mês), Multi-AZ no RDS (dobra o preço), Secrets Manager (US$ 0,40 por segredo),
Performance Insights e Enhanced Monitoring.

## Crescendo

Quando um servidor não bastar, nesta ordem:

1. **Instância maior** (`t3.small`/`t3.medium`): troque `instance_type` e `terraform apply` (reinicia a EC2).
2. **Load balancer + ACM**: ALB com certificado do ACM na frente, a EC2 vira alvo; tira o TLS do nginx e
   permite deploy sem downtime com duas instâncias.
3. **Várias instâncias**: Auto Scaling Group atrás do ALB. Antes, leia
   [Escalando para mais de uma réplica](deployment.md#escalando-para-mais-de-uma-réplica) (rate limit e cache em memória).
4. **RDS Multi-AZ** para failover automático do banco.
5. **CloudFront** na frente para os estáticos e o PWA.
6. **ECS/Fargate ou EKS**: os manifests de [`deploy/k8s`](../deploy/k8s) já existem.

## Sem Terraform (pelo console)

Mesmo resultado, à mão:

1. **EC2 → Security Groups:** `self-control-app` com entrada 80 e 443 de `0.0.0.0/0` e 22 só do seu IP.
   `self-control-db` com entrada 5432 vinda do grupo `self-control-app`.
2. **IAM → Roles:** papel para EC2 com a política `AmazonSSMManagedInstanceCore`.
3. **EC2 → Launch instance:** Ubuntu 24.04, `t2.micro`, 20 GB gp3, o security group e o papel acima, seu key
   pair, e em *Advanced details → User data* cole o conteúdo de `deploy/aws/server/bootstrap.sh`.
   Em *Metadata version* escolha *V2 only*.
4. **EC2 → Elastic IPs:** aloque e associe à instância.
5. **RDS → Create database:** PostgreSQL 17, template *Free tier*, `db.t3.micro`, 20 GB, sem acesso público,
   security group `self-control-db`, backup de 7 dias (1 no plano Free). Não preencha *Initial database name*
   (o `create-db-user.sh` cria o banco com o dono certo).
6. **CloudWatch → Alarms** e **Billing → Budgets** como na tabela de monitoramento.
7. **IAM → Identity providers:** adicione `token.actions.githubusercontent.com` (audiência
   `sts.amazonaws.com`) e um papel que ele possa assumir, restrito a
   `repo:luan-vega/self-control-financial:environment:production`, com `ssm:SendCommand` na instância e no
   documento `AWS-RunShellScript` e `ssm:GetCommandInvocation`. O `iam.tf` tem a política exata.

Depois siga do passo 2 em diante.

## Problemas comuns

| Sintoma | Causa provável e solução |
|---|---|
| `terraform apply`: *backup retention period exceeds the maximum available to free tier customers* | Conta no plano Free: `db_backup_retention_days = 1` |
| `terraform apply`: *instance type is not eligible for Free Tier* | Use `t3.micro` (plano Free ou região sem t2) |
| `terraform apply`: *Provider with url https://token.actions.githubusercontent.com already exists* | A conta já tem o provedor OIDC: `create_github_oidc_provider = false` |
| Cannot find version 17 for postgres | Liste as versões da região (`aws rds describe-db-engine-versions --engine postgres --query 'DBEngineVersions[].EngineVersion'`) e ajuste `db_engine_version` |
| `docker`, `nginx` ou `/opt/self-control` não existem | O bootstrap ainda roda ou falhou: `cloud-init status --long` e `/var/log/cloud-init-output.log`. Pode rodar de novo: `sudo bash /tmp/self-control/bootstrap.sh` |
| Workflow: *Not authorized to perform sts:AssumeRoleWithWebIdentity* | O job não está no environment `production`, ou `github_repo`/`github_environment` no Terraform não batem com o repositório |
| Workflow: `InvalidInstanceId` no SSM | O agente do SSM não registrou a instância: confira o papel da EC2 e `sudo snap services amazon-ssm-agent` |
| Deploy: *não baixou a imagem* | Pacote privado sem `docker login ghcr.io` (passo 6), ou a tag ainda não foi publicada pelo Release |
| App reinicia em loop, log com *BYPASSRLS* | `DB_USER` é o `postgres`: rode `create-db-user.sh` e use o usuário `selfcontrol` |
| App não conecta no banco (*Connection refused/timeout*) | Security group do RDS sem a regra vinda da EC2, ou `DB_URL` com host errado. Teste com `psql` do servidor |
| *no pg_hba.conf entry ... no encryption* | Falta `?sslmode=require` no `DB_URL` |
| Container morre com `OutOfMemoryError` ou *Killed* | Pouca memória: `free -m`, confira se o swap está ativo (`swapon --show`). Se persistir, `t3.small` |
| Site lento depois de um tempo, CPU parada em ~10% | Créditos de CPU esgotados (alarme `ec2-cpu-credits`). Veja quem consome (`docker stats`, `top`) ou suba de instância |
| 502/página "Atualizando" que não some | A app não subiu: `docker ps`, `docker logs self-control-app --tail 100` |
| Página "Configurando HTTPS" | `setup-https.sh` não terminou. Rode de novo e veja o erro do certbot |
| certbot: *unauthorized / connection refused* | DNS ainda não aponta para o IP elástico, ou porta 80 fechada no security group |
| Redireciona em loop / *HTTPS obrigatório* | Acesso direto na porta 8080 ou proxy sem `X-Forwarded-Proto`. Sempre pelo nginx |
| Login cai a todo momento | `APP_PUBLIC_URL` diferente do domínio acessado (cookie `Secure` + redirecionamentos) |
| Cobrança inesperada | Billing → Bills, por serviço. Os suspeitos de sempre: IP elástico solto (sem instância), snapshots antigos, instância fora do free tier, segunda instância esquecida |

## Desmontar

```bash
# 1. Desligue a proteção do banco
terraform apply -var db_deletion_protection=false
# 2. Apague tudo (um snapshot final "self-control-db-final" é criado e fica, apague depois se quiser)
terraform destroy
```
