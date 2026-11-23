provider "aws" {
  region = var.region
}

# Public IP of this laptop, so the EC2 security group can be scoped to it without a manual step.
data "http" "my_ip" {
  url = "https://checkip.amazonaws.com"
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_subnet" "by_id" {
  for_each = toset(data.aws_subnets.default.ids)
  id       = each.value
}

# D2: the generator and the database must share an AZ. Pin one and place both in it.
locals {
  my_cidr       = "${chomp(data.http.my_ip.response_body)}/32"
  subnets_by_az = { for s in data.aws_subnet.by_id : s.availability_zone => s.id... }
  benchmark_az  = sort(keys(local.subnets_by_az))[0]
  ec2_subnet_id = local.subnets_by_az[local.benchmark_az][0]
}

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

resource "aws_key_pair" "laptop" {
  key_name   = "uuidv7-benchmark"
  public_key = file(pathexpand(var.public_key_path))
}

resource "random_password" "db" {
  length  = 24
  special = false
}

# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

resource "aws_security_group" "ec2" {
  name        = "uuidv7-benchmark-ec2"
  description = "Load generator and Ktor server"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "ssh from the laptop only"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [local.my_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "rds" {
  name        = "uuidv7-benchmark-rds"
  description = "Postgres, reachable only from the generator"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "postgres from the EC2 security group only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }
}

resource "aws_db_subnet_group" "benchmark" {
  name       = "uuidv7-benchmark"
  subnet_ids = data.aws_subnets.default.ids
}

# ---------------------------------------------------------------------------
# Enhanced Monitoring
#
# The CPU credit metrics in CloudWatch are published at a five-minute frequency, which cannot
# resolve a 20s sweep run. Enhanced Monitoring at 1s writes OS metrics to the RDSOSMetrics
# CloudWatch Logs group instead, and credit burn is derived from that CPU series.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "rds_monitoring_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "rds_monitoring" {
  name               = "uuidv7-benchmark-rds-monitoring"
  assume_role_policy = data.aws_iam_policy_document.rds_monitoring_assume.json
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

resource "aws_db_instance" "benchmark" {
  identifier     = "uuidv7-benchmark"
  engine         = "postgres"
  engine_version = "16" # D3: production targets 16.x, not 18
  instance_class = var.db_instance_class

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db.result

  # gp3 under 400 GB: 3000 IOPS / 125 MBps, not configurable (R4).
  allocated_storage = var.db_allocated_storage
  storage_type      = "gp3"

  availability_zone      = local.benchmark_az
  db_subnet_group_name   = aws_db_subnet_group.benchmark.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  # 1s OS metrics -> CloudWatch Logs group RDSOSMetrics. This is the credit-burn source.
  monitoring_interval = 1
  monitoring_role_arn = aws_iam_role.rds_monitoring.arn

  performance_insights_enabled          = true
  performance_insights_retention_period = 7 # free tier

  # D1: the default parameter group computes shared_buffers as DBInstanceClassMemory/32768,
  # which on this 16 GiB class is ~4 GiB. Verified with SHOW shared_buffers, not assumed.

  # R3: without both of these, destroy leaves a snapshot billing quietly forever.
  skip_final_snapshot = true
  deletion_protection = false

  apply_immediately          = true
  auto_minor_version_upgrade = false
}

# ---------------------------------------------------------------------------
# Load generator
# ---------------------------------------------------------------------------

resource "aws_instance" "generator" {
  ami                         = data.aws_ssm_parameter.al2023_arm64.value
  instance_type               = var.ec2_instance_class
  subnet_id                   = local.ec2_subnet_id
  vpc_security_group_ids      = [aws_security_group.ec2.id]
  key_name                    = aws_key_pair.laptop.key_name
  associate_public_ip_address = true

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }

  user_data = <<-EOT
    #!/bin/bash
    dnf install -y java-21-amazon-corretto-headless postgresql16
  EOT

  tags = { Name = "uuidv7-benchmark-generator" }
}
