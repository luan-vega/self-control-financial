output "public_ip" {
  description = "IP elástico da EC2: aponte o registro A do domínio para ele"
  value       = aws_eip.app.public_ip
}

output "instance_id" {
  description = "Vai na variável EC2_INSTANCE_ID do environment production no GitHub"
  value       = aws_instance.app.id
}

output "ssh_command" {
  value = var.ssh_public_key == "" ? "Sem key pair: aws ssm start-session --target ${aws_instance.app.id} --region ${var.region}" : "ssh ubuntu@${aws_eip.app.public_ip}"
}

output "db_endpoint" {
  description = "Host do RDS (só acessível de dentro da EC2)"
  value       = aws_db_instance.main.address
}

output "db_master_password" {
  description = "Senha do usuário postgres do RDS. Veja com: terraform output -raw db_master_password"
  value       = random_password.db_master.result
  sensitive   = true
}

output "github_deploy_role_arn" {
  description = "Vai na variável AWS_DEPLOY_ROLE_ARN do environment production no GitHub"
  value       = aws_iam_role.github_deploy.arn
}

output "next_steps" {
  value = <<-EOT
    1. DNS: registro A de ${var.domain_name != "" ? var.domain_name : "<seu domínio>"} -> ${aws_eip.app.public_ip}${var.route53_zone_id != "" ? " (já criado no Route 53)" : ""}
    2. GitHub (Settings -> Environments -> ${var.github_environment} -> Variables):
         AWS_REGION=${var.region}
         AWS_DEPLOY_ROLE_ARN=${aws_iam_role.github_deploy.arn}
         EC2_INSTANCE_ID=${aws_instance.app.id}
    3. Continue em docs/deploy-aws.md, passo 4 (copiar os arquivos e criar o usuário do banco)
  EOT
}
