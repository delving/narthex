# Narthex Deployment Quick Start

## Overview

Narthex deployment is handled through a separate private repository that contains deployment scripts, configuration management, and documentation.

## Deployment Repository

The deployment tools are located in a private repository (not public with the main codebase):

- **Repository**: `narthex-deployment` (private)
- **Location**: Contact your system administrator for access

## Quick Reference

### Prerequisites

1. Access to the `narthex-deployment` repository
2. Access to the `narthex-configs` repository (organization-specific configs)
3. SSH access to target servers
4. SBT installed for building

### Basic Deployment

```bash
# Set environment variables
export NARTHEX_REPO=/path/to/narthex          # This repository
export NARTHEX_CONFIG_REPO=/path/to/narthex-configs  # Config repository

# Deploy to an organization
cd /path/to/narthex-deployment
make deploy ORG=myorg
```

### Repository Structure

```
narthex/                    # Main application code (public)
├── app/                   # Scala application code
├── conf/                  # Base configuration
├── docs/                  # Documentation
└── ...

narthex-deployment/        # Deployment tools (private)
├── scripts/               # Deployment scripts
├── docs/                  # Deployment documentation
└── Makefile              # Deployment commands

narthex-configs/           # Organization configs (private)
├── orgA/                 # Organization A configuration
├── orgB/                 # Organization B configuration
└── ...
```

## For More Information

See the **narthex-deployment** repository for:

- Complete deployment documentation
- Configuration repository setup guide
- Deployment scripts
- Multi-organization deployment instructions
- Troubleshooting guides

## Contact

For deployment access or questions, contact your system administrator.

---

**Note**: This document provides only a quick reference. Complete deployment documentation is maintained in the private `narthex-deployment` repository.
