# Alarmes do CloudWatch (o free tier cobre 10) e orçamento mensal (2 orçamentos grátis).
# As métricas básicas da EC2 (5 min) e do RDS (1 min) já vêm ligadas sem custo.

locals {
  alerts_enabled = var.alert_email != ""
  alarm_actions  = local.alerts_enabled ? [aws_sns_topic.alerts[0].arn] : []
}

resource "aws_sns_topic" "alerts" {
  count = local.alerts_enabled ? 1 : 0
  name  = "${var.project}-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  count     = local.alerts_enabled ? 1 : 0
  topic_arn = aws_sns_topic.alerts[0].arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# Falha de hardware/host: a AWS move a instância para outro host, mantendo IP, disco e id
resource "aws_cloudwatch_metric_alarm" "ec2_system_check" {
  alarm_name          = "${var.project}-ec2-system-check"
  alarm_description   = "Falha no host da EC2: recuperação automática disparada"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_System"
  dimensions          = { InstanceId = aws_instance.app.id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  alarm_actions       = concat(["arn:aws:automate:${var.region}:ec2:recover"], local.alarm_actions)
}

# Sistema operacional travado (sem memória, kernel): reinicia a instância
resource "aws_cloudwatch_metric_alarm" "ec2_instance_check" {
  alarm_name          = "${var.project}-ec2-instance-check"
  alarm_description   = "EC2 não responde: reboot automático"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_Instance"
  dimensions          = { InstanceId = aws_instance.app.id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  alarm_actions       = concat(["arn:aws:automate:${var.region}:ec2:reboot"], local.alarm_actions)
}

resource "aws_cloudwatch_metric_alarm" "ec2_cpu" {
  alarm_name          = "${var.project}-ec2-cpu"
  alarm_description   = "CPU da EC2 acima de 80% por 15 min (no t2/t3 os créditos de CPU acabam e tudo fica lento)"
  namespace           = "AWS/EC2"
  metric_name         = "CPUUtilization"
  dimensions          = { InstanceId = aws_instance.app.id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "ec2_cpu_credits" {
  alarm_name          = "${var.project}-ec2-cpu-credits"
  alarm_description   = "Créditos de CPU da EC2 acabando: a instância vai cair para 10% de CPU"
  namespace           = "AWS/EC2"
  metric_name         = "CPUCreditBalance"
  dimensions          = { InstanceId = aws_instance.app.id }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 10
  comparison_operator = "LessThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.project}-rds-cpu"
  alarm_description   = "CPU do RDS acima de 80% por 15 min"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_storage" {
  alarm_name          = "${var.project}-rds-storage"
  alarm_description   = "Menos de 2 GB livres no RDS"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2 * 1024 * 1024 * 1024
  comparison_operator = "LessThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_memory" {
  alarm_name          = "${var.project}-rds-memory"
  alarm_description   = "Menos de 64 MB de memória livre no RDS por 15 min"
  namespace           = "AWS/RDS"
  metric_name         = "FreeableMemory"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 64 * 1024 * 1024
  comparison_operator = "LessThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_budgets_budget" "monthly" {
  count = local.alerts_enabled && var.monthly_budget_usd > 0 ? 1 : 0

  name         = "${var.project}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}
