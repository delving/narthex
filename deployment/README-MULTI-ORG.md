# Narthex Multi-Organization Deployment Guide

This guide explains how to run multiple Narthex organizations on a single server using systemd templates and dynamic nginx configuration.

## Overview

The multi-org setup provides:
- **Systemd template service** (`narthex@.service`) for managing instances
- **Dynamic port allocation** with a registry system
- **Nginx configuration** with subdomain and path-based routing
- **Management tools** for easy administration

## Quick Start

### 1. Initial Setup

Run the setup script as root:
```bash
cd /opt/narthex
sudo bash deployment/scripts/setup-multi-org.sh
```

### 2. Register Organizations

Register each organization with automatic port assignment:
```bash
# Register with auto-assigned port
sudo narthex-config register brabantcloud

# Register with specific port
sudo narthex-config register natarch --port 9002
```

### 3. Start Services

```bash
# Start a specific organization
sudo narthex-org start brabantcloud

# Enable auto-start on boot
sudo narthex-org enable brabantcloud

# Start all registered organizations
sudo narthex-config list | grep -v "^Organization" | awk '{print $1}' | xargs -I {} sudo systemctl start narthex@{}
```

### 4. Configure Nginx

The nginx configuration is automatically generated:
```bash
# Update nginx config files
sudo narthex-config update-nginx

# Test nginx configuration
sudo nginx -t

# Reload nginx
sudo systemctl reload nginx
```

## Architecture

### Directory Structure
```
/etc/narthex/
├── registry.json           # Organization registry with port mappings
├── template.conf          # Template configuration file
├── logback-template.xml   # Logging template
├── brabantcloud.conf      # Organization-specific config
├── brabantcloud.env       # Environment variables
├── natarch.conf
└── natarch.env

/home/narthex/NarthexFiles/
├── brabantcloud/          # Data for brabantcloud
├── natarch/              # Data for natarch
└── ...

/etc/nginx/conf.d/
├── narthex-upstreams.conf # Auto-generated upstream definitions
└── narthex-server.conf    # Auto-generated server blocks
```

### Port Management

The system maintains a registry (`/etc/narthex/registry.json`) that tracks:
- Organization IDs
- Assigned ports (starting from 9001)
- Configuration file paths
- Enabled/disabled status

Example registry:
```json
{
  "organizations": {
    "brabantcloud": {
      "port": 9001,
      "config_file": "/etc/narthex/brabantcloud.conf",
      "enabled": true
    },
    "natarch": {
      "port": 9002,
      "config_file": "/etc/narthex/natarch.conf",
      "enabled": true
    }
  },
  "next_port": 9003
}
```

### Systemd Template

The template service (`narthex@.service`) uses:
- Instance name as organization ID
- Environment file: `/etc/narthex/%i.env`
- Config file: `/etc/narthex/%i.conf`
- Log file: `/var/log/narthex/narthex-%i.log`

### Nginx Routing

Two routing methods are supported:

#### 1. Subdomain-based
- `brabantcloud.narthex.yourdomain.com` → port 9001
- `natarch.narthex.yourdomain.com` → port 9002

#### 2. Path-based
- `narthex.yourdomain.com/brabantcloud/` → port 9001
- `narthex.yourdomain.com/natarch/` → port 9002

## Management Commands

### narthex-config
```bash
# Register new organization
narthex-config register <org-id> [--port PORT] [--template FILE]

# List all organizations
narthex-config list

# Enable/disable organization
narthex-config enable <org-id>
narthex-config disable <org-id>

# Update nginx configuration
narthex-config update-nginx
```

### narthex-org
```bash
# Service management
narthex-org start <org-id>
narthex-org stop <org-id>
narthex-org restart <org-id>
narthex-org status <org-id>

# Auto-start management
narthex-org enable <org-id>
narthex-org disable <org-id>

# View logs
narthex-org logs <org-id>
```

### narthex-monitor
```bash
# Show status of all instances
narthex-monitor
```

## Adding a New Organization

Complete example for adding a new organization:

```bash
# 1. Register the organization
sudo narthex-config register museumx --java-opts "-Xmx4g -Xms1g"

# 2. Edit the generated config if needed
sudo nano /etc/narthex/museumx.conf

# 3. Create Fuseki dataset
curl -X POST http://localhost:3030/$/datasets \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "dbName=museumx&dbType=tdb2"

# 4. Start the service
sudo narthex-org start museumx

# 5. Enable auto-start
sudo narthex-org enable museumx

# 6. Update nginx
sudo narthex-config update-nginx
sudo systemctl reload nginx
```

## Monitoring and Troubleshooting

### Check Status
```bash
# All instances
narthex-monitor

# Specific instance
systemctl status narthex@brabantcloud

# Check ports
ss -tlnp | grep java
```

### View Logs
```bash
# Systemd journal
journalctl -u narthex@brabantcloud -f

# Application logs
tail -f /var/log/narthex/narthex-brabantcloud.log
```

### Common Issues

1. **Port conflicts**: Check registry.json and ensure ports are unique
2. **Permission issues**: Ensure narthex user owns data directories
3. **Fuseki connection**: Verify Fuseki datasets exist for each org
4. **Nginx 502 errors**: Check if the backend service is running

## Security Considerations

1. **Systemd hardening**: The template includes security restrictions
2. **File permissions**: Configs are readable only by narthex user
3. **Network isolation**: Services bind to localhost only
4. **Resource limits**: Each instance has separate memory limits

## Backup and Migration

### Backup Organization
```bash
# Stop service
sudo narthex-org stop brabantcloud

# Backup data
tar -czf brabantcloud-backup.tar.gz \
  /home/narthex/NarthexFiles/brabantcloud \
  /etc/narthex/brabantcloud.* \
  
# Backup Fuseki data
fuseki-backup --dataset brabantcloud
```

### Migrate to Another Server
1. Copy backup files to new server
2. Run setup script
3. Register organization with same port
4. Restore data directories
5. Import Fuseki data
6. Start service

## Performance Tuning

### Java Memory Settings
Edit `/etc/narthex/<org-id>.env`:
```bash
JAVA_OPTS="-Xmx4g -Xms1g -XX:+UseG1GC"
```

### Concurrent Processing
Edit organization config to adjust:
```hocon
contexts {
  dataset-harvesting-execution-context {
    fork-join-executor {
      parallelism-max = 4
    }
  }
}
```

### Nginx Optimization
Add to server blocks:
```nginx
# Connection pooling
upstream narthex_brabantcloud {
    server 127.0.0.1:9001 max_fails=3 fail_timeout=30s;
    keepalive 32;
}
```