variable "region" {
  description = "Region to provision in. Does not affect the numbers, only ssh RTT from the laptop."
  type        = string
  default     = "eu-central-1"
}

variable "db_instance_class" {
  description = <<-EOT
    D4. Burstable by choice. On RDS this is always Unlimited mode — the engine has no
    standard/throttling mode to select — so this instance bursts past baseline and bills
    surplus credits instead of clamping. See AWS-BENCHMARK-PLAN.md D4/D5.
  EOT
  type        = string
  default     = "db.t4g.xlarge"
}

variable "ec2_instance_class" {
  description = "Runs both the Ktor server and the LoadTest generator. Escalate to c7g.2xlarge if sched lag rises (R5)."
  type        = string
  default     = "c7g.xlarge"
}

variable "db_allocated_storage" {
  description = <<-EOT
    gp3 below 400 GB is fixed at 3000 IOPS / 125 MBps and iops/storage_throughput cannot be
    set. Raising this to 400 is the only lever if the load is throughput-bound (R4).
  EOT
  type        = number
  default     = 50
}

variable "public_key_path" {
  type    = string
  default = "~/.ssh/id_ed25519.pub"
}

variable "db_name" {
  type    = string
  default = "uuidv7_playground"
}

variable "db_username" {
  type    = string
  default = "benchmark"
}
