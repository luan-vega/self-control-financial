# Ubuntu 24.04 LTS mais recente, publicado pela Canonical (o parâmetro do SSM sempre aponta para a última AMI).
# Mudar a AMI recriaria a EC2, então novas versões são ignoradas depois da criação (ver lifecycle abaixo).
data "aws_ssm_parameter" "ubuntu_ami" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
}

resource "aws_key_pair" "admin" {
  count = var.ssh_public_key == "" ? 0 : 1

  key_name   = "${var.project}-admin"
  public_key = var.ssh_public_key
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.ubuntu_ami.value
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.app.id]
  key_name               = var.ssh_public_key == "" ? null : aws_key_pair.admin[0].key_name
  iam_instance_profile   = aws_iam_instance_profile.app.name

  # Instala Docker, nginx, certbot, swap e as pastas da app no primeiro boot (log em /var/log/cloud-init-output.log)
  user_data                   = file("${path.module}/../server/bootstrap.sh")
  user_data_replace_on_change = false

  # Créditos de CPU: "standard" nunca cobra a mais (no t3, o padrão "unlimited" pode cobrar)
  credit_specification {
    cpu_credits = "standard"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_gb
    encrypted             = true
    delete_on_termination = true
  }

  # Só IMDSv2 (protege as credenciais da instância contra SSRF)
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  tags = { Name = "${var.project}-app" }

  lifecycle {
    ignore_changes = [ami, user_data]
  }
}

# IP fixo: o DNS aponta para ele e não muda quando a instância é parada e ligada de novo
resource "aws_eip" "app" {
  domain   = "vpc"
  instance = aws_instance.app.id

  tags = { Name = "${var.project}-app" }
}

resource "aws_route53_record" "app" {
  count = var.route53_zone_id != "" && var.domain_name != "" ? 1 : 0

  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"
  ttl     = 300
  records = [aws_eip.app.public_ip]
}
