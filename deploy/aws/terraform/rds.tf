# Senha do usuário mestre (postgres). Só serve para criar o usuário da app (create-db-user.sh);
# a app usa um usuário próprio, sem superusuário nem BYPASSRLS, para o Row-Level Security valer.
resource "random_password" "db_master" {
  length  = 32
  special = true
  # Caracteres que o RDS aceita e que não quebram URL nem shell
  override_special = "-_~"
}

resource "aws_db_subnet_group" "main" {
  name       = "${var.project}-db"
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_db_parameter_group" "main" {
  name   = "${var.project}-pg${var.db_engine_version}"
  family = "postgres${var.db_engine_version}"

  # Conexões só com TLS
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # Registra queries lentas (mais de 1 s) no log do RDS
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "main" {
  identifier     = "${var.project}-db"
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  # Sem db_name: o banco "selfcontrol" é criado por create-db-user.sh, já com o usuário da app como dono
  username = "postgres"
  password = random_password.db_master.result
  port     = 5432

  allocated_storage     = var.db_storage_gb
  max_allocated_storage = 0 # sem autoscaling de disco: passar de 20 GB sai do free tier
  storage_type          = "gp2"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  parameter_group_name   = aws_db_parameter_group.main.name
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period  = var.db_backup_retention_days
  backup_window            = "06:00-06:30" # 03:00 em Brasília
  maintenance_window       = "sun:06:30-sun:07:30"
  copy_tags_to_snapshot    = true
  delete_automated_backups = false

  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false
  apply_immediately           = false

  # Performance Insights e Enhanced Monitoring ficam desligados (o básico do CloudWatch é grátis)
  performance_insights_enabled = false
  monitoring_interval          = 0

  deletion_protection       = var.db_deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.project}-db-final"

  tags = { Name = "${var.project}-db" }
}
