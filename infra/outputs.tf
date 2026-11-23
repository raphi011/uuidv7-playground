output "ec2_public_ip" {
  value = aws_instance.generator.public_ip
}

output "ssh" {
  value = "ssh ec2-user@${aws_instance.generator.public_ip}"
}

output "rds_endpoint" {
  value = aws_db_instance.benchmark.endpoint
}

# Names the RDSOSMetrics CloudWatch Logs stream that carries the 1s CPU series.
output "db_resource_id" {
  value = aws_db_instance.benchmark.resource_id
}

output "db_instance_identifier" {
  value = aws_db_instance.benchmark.identifier
}

output "availability_zone" {
  value = local.benchmark_az
}

# Sourced on the EC2 box before running the loader or the sweep.
output "env" {
  sensitive = true
  value     = <<-EOT
    export PGURL='jdbc:postgresql://${aws_db_instance.benchmark.endpoint}/${var.db_name}'
    export PGUSER='${var.db_username}'
    export PGPASSWORD='${random_password.db.result}'
    export PGHOST='${aws_db_instance.benchmark.address}'
    export PGDATABASE='${var.db_name}'
  EOT
}

output "region" {
  value = var.region
}
