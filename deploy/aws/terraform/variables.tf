variable "region" {
  description = "Região da AWS. sa-east-1 (São Paulo) tem a menor latência no Brasil; us-east-1 costuma ser mais barata depois do free tier."
  type        = string
  default     = "sa-east-1"
}

variable "project" {
  description = "Prefixo dos nomes e tag Project dos recursos"
  type        = string
  default     = "self-control"
}

# --- EC2 ---------------------------------------------------------------------------------

variable "instance_type" {
  description = "t2.micro no free tier clássico (12 meses). Contas criadas depois de 15/07/2025 (plano Free) usam t3.micro."
  type        = string
  default     = "t2.micro"
}

variable "root_volume_gb" {
  description = "Disco da EC2 (gp3). O free tier cobre até 30 GB de EBS."
  type        = number
  default     = 20
}

variable "ssh_public_key" {
  description = "Chave pública para o usuário ubuntu (conteúdo de ~/.ssh/id_ed25519.pub). Vazio = sem key pair, só SSM Session Manager."
  type        = string
  default     = ""
}

variable "ssh_allowed_cidrs" {
  description = "IPs que podem abrir SSH (porta 22), ex.: [\"203.0.113.4/32\"]. Vazio = porta 22 fechada (use o SSM Session Manager)."
  type        = list(string)
  default     = []
}

# --- RDS ---------------------------------------------------------------------------------

variable "db_instance_class" {
  description = "db.t3.micro ou db.t4g.micro ficam no free tier (750 h/mês)"
  type        = string
  default     = "db.t3.micro"
}

variable "db_engine_version" {
  description = "Versão major do PostgreSQL (a app exige 16+). Confira as disponíveis com: aws rds describe-db-engine-versions --engine postgres"
  type        = string
  default     = "17"
}

variable "db_storage_gb" {
  description = "Armazenamento do RDS. O free tier cobre 20 GB."
  type        = number
  default     = 20
}

variable "db_backup_retention_days" {
  description = "Dias de backup automático. Contas no plano Free (criadas depois de 15/07/2025) só aceitam 1; o free tier clássico aceita 7."
  type        = number
  default     = 7
}

variable "db_deletion_protection" {
  description = "Impede apagar o banco por engano (inclusive com terraform destroy). Desligue antes de destruir de propósito."
  type        = bool
  default     = true
}

# --- Deploy (GitHub Actions) ------------------------------------------------------------

variable "github_repo" {
  description = "Repositório que pode fazer deploy via OIDC (dono/nome)"
  type        = string
  default     = "luan-vega/self-control-financial"
}

variable "github_environment" {
  description = "Environment do GitHub usado pelo workflow deploy-aws.yml (o OIDC só aceita tokens dele)"
  type        = string
  default     = "production"
}

variable "create_github_oidc_provider" {
  description = "Cria o provedor OIDC do GitHub na conta. Use false se a conta já tiver um (só pode existir um por conta)."
  type        = bool
  default     = true
}

# --- DNS e alertas -----------------------------------------------------------------------

variable "domain_name" {
  description = "Domínio da app, ex.: financas.seudominio.com.br. Só é usado para criar o registro no Route 53."
  type        = string
  default     = ""
}

variable "route53_zone_id" {
  description = "Hosted zone do Route 53 onde criar o registro A. Vazio = você aponta o DNS no seu provedor."
  type        = string
  default     = ""
}

variable "alert_email" {
  description = "E-mail que recebe alarmes do CloudWatch e do orçamento (confirme a inscrição no e-mail da AWS). Vazio = sem alertas."
  type        = string
  default     = ""
}

variable "monthly_budget_usd" {
  description = "Orçamento mensal em USD. Avisa em 80% do gasto real e em 100% do previsto. 0 = sem orçamento."
  type        = number
  default     = 5
}
