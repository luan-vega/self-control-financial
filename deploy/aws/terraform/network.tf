# Usa a VPC padrão da região: subnets públicas em todas as AZs, sem NAT Gateway (que custaria ~US$ 32/mês).
# A EC2 fica numa subnet pública com IP elástico; o RDS fica sem IP público, acessível só pela EC2.

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

resource "aws_security_group" "app" {
  name        = "${var.project}-app"
  description = "EC2 da app: HTTP/HTTPS publicos, SSH so dos IPs liberados"
  vpc_id      = data.aws_vpc.default.id

  tags = { Name = "${var.project}-app" }
}

resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.app.id
  description       = "HTTP (redireciona para HTTPS e valida o Let's Encrypt)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each = toset(var.ssh_allowed_cidrs)

  security_group_id = aws_security_group.app.id
  description       = "SSH de ${each.value}"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
}

resource "aws_vpc_security_group_egress_rule" "app_all" {
  security_group_id = aws_security_group.app.id
  description       = "Saida livre (imagens do GHCR, apt, Let's Encrypt, Stripe, SSM)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "db" {
  name        = "${var.project}-db"
  description = "RDS PostgreSQL: so a EC2 da app entra"
  vpc_id      = data.aws_vpc.default.id

  tags = { Name = "${var.project}-db" }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL a partir da EC2"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
