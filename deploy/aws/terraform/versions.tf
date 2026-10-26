terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Estado local (terraform.tfstate) por padrão. Ele guarda a senha mestre do RDS: não faça commit.
  # Para guardar no S3, descomente e crie o bucket antes (docs/deploy-aws.md, "Estado do Terraform").
  # backend "s3" {
  #   bucket       = "self-control-tfstate-<sua-conta>"
  #   key          = "prod/terraform.tfstate"
  #   region       = "sa-east-1"
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
    }
  }
}
