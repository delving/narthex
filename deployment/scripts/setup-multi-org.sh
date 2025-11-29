#!/bin/bash
# Setup script for multi-organization Narthex deployment

set -e

# Configuration
NARTHEX_USER="narthex"
NARTHEX_GROUP="narthex"
NARTHEX_HOME="/opt/narthex"
CONFIG_DIR="/etc/narthex"
LOG_DIR="/var/log/narthex"
RUN_DIR="/var/run/narthex"
DATA_DIR="/home/narthex/NarthexFiles"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Narthex Multi-Organization Setup${NC}"
echo "=================================="

# Check if running as root
if [[ $EUID -ne 0 ]]; then
   echo -e "${RED}This script must be run as root${NC}" 
   exit 1
fi

# Create narthex user if doesn't exist
if ! id "$NARTHEX_USER" &>/dev/null; then
    echo "Creating narthex user..."
    useradd -r -s /bin/false -d /home/narthex -m $NARTHEX_USER
fi

# Create directories
echo "Creating directories..."
mkdir -p $CONFIG_DIR
mkdir -p $LOG_DIR
mkdir -p $RUN_DIR
mkdir -p $DATA_DIR
mkdir -p /etc/systemd/system

# Set permissions
chown -R $NARTHEX_USER:$NARTHEX_GROUP $LOG_DIR $RUN_DIR $DATA_DIR
chmod 755 $CONFIG_DIR

# Copy systemd template
echo "Installing systemd template..."
cp deployment/systemd/narthex@.service /etc/systemd/system/

# Create template configuration
echo "Creating template configuration..."
cat > $CONFIG_DIR/template.conf << 'EOF'
# Template configuration for Narthex organizations
# This file will be copied and modified for each organization

include "application.conf"

# Organization ID - will be replaced
orgId = "ORGID_PLACEHOLDER"

# Paths
narthexHome = "/home/narthex/NarthexFiles/ORGID_PLACEHOLDER"

# Triple store - each org gets its own dataset
triple-store = "http://localhost:3030/ORGID_PLACEHOLDER"
sparql-query-path = "/query"
sparql-update-path = "/update"
graph-store-path = "/data"
graph-store-param = "graph"

# RDF Base URL
rdfBaseUrl = "http://data.ORGID_PLACEHOLDER.nl"

# Features
thesaurus = true
categories = false
enableIncrementalHarvest = true

# Email notifications
emailReportsTo = []

# Performance
akka {
  default-dispatcher.core-pool-size-max = 32
  loglevel = "INFO"
}

contexts {
  dataset-harvesting-execution-context {
    fork-join-executor {
      parallelism-max = 2
    }
  }
}

# Play configuration
play.http.parser.maxDiskBuffer = 250MB
EOF

# Create logback template
echo "Creating logback template..."
cat > $CONFIG_DIR/logback-template.xml << 'EOF'
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/narthex/narthex-ORGID_PLACEHOLDER.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/narthex/narthex-ORGID_PLACEHOLDER.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%date{ISO8601} [%level] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%date{ISO8601} [%level] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="play" level="INFO"/>
    <logger name="application" level="INFO"/>
    <logger name="org.narthex" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="FILE"/>
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
EOF

# Install Python dependencies
echo "Installing Python dependencies..."
apt-get update
apt-get install -y python3 python3-pip
pip3 install --upgrade pip

# Make scripts executable
chmod +x deployment/scripts/narthex-config-generator.py
ln -sf $PWD/deployment/scripts/narthex-config-generator.py /usr/local/bin/narthex-config

# Create helper scripts
echo "Creating helper scripts..."

# narthex-org script for managing organizations
cat > /usr/local/bin/narthex-org << 'EOF'
#!/bin/bash
# Helper script for managing Narthex organizations

case "$1" in
    start)
        systemctl start narthex@$2
        ;;
    stop)
        systemctl stop narthex@$2
        ;;
    restart)
        systemctl restart narthex@$2
        ;;
    status)
        systemctl status narthex@$2
        ;;
    enable)
        systemctl enable narthex@$2
        ;;
    disable)
        systemctl disable narthex@$2
        ;;
    logs)
        journalctl -u narthex@$2 -f
        ;;
    *)
        echo "Usage: narthex-org {start|stop|restart|status|enable|disable|logs} <org-id>"
        exit 1
        ;;
esac
EOF

chmod +x /usr/local/bin/narthex-org

# Create monitoring script
cat > /usr/local/bin/narthex-monitor << 'EOF'
#!/bin/bash
# Monitor all Narthex instances

echo "Narthex Instance Status"
echo "======================"
echo ""
printf "%-20s %-10s %-6s %-20s\n" "Organization" "Status" "Port" "Memory"
echo "------------------------------------------------------------"

for service in $(systemctl list-units --type=service --state=running,failed | grep "narthex@" | awk '{print $1}'); do
    org_id=$(echo $service | sed 's/narthex@\(.*\)\.service/\1/')
    status=$(systemctl is-active $service)
    
    if [ "$status" = "active" ]; then
        # Get port from environment file
        if [ -f "/etc/narthex/${org_id}.env" ]; then
            port=$(grep "^PORT=" "/etc/narthex/${org_id}.env" | cut -d= -f2)
        else
            port="N/A"
        fi
        
        # Get memory usage
        pid=$(systemctl show -p MainPID --value $service)
        if [ "$pid" != "0" ]; then
            memory=$(ps -o rss= -p $pid 2>/dev/null | awk '{printf "%.1f MB", $1/1024}')
        else
            memory="N/A"
        fi
        
        printf "%-20s %-10s %-6s %-20s\n" "$org_id" "$status" "$port" "$memory"
    else
        printf "%-20s %-10s %-6s %-20s\n" "$org_id" "$status" "-" "-"
    fi
done
EOF

chmod +x /usr/local/bin/narthex-monitor

# Reload systemd
systemctl daemon-reload

echo -e "${GREEN}Setup complete!${NC}"
echo ""
echo "Next steps:"
echo "1. Register organizations:"
echo "   narthex-config register <org-id> [--port PORT]"
echo ""
echo "2. Start organizations:"
echo "   narthex-org start <org-id>"
echo ""
echo "3. Enable auto-start:"
echo "   narthex-org enable <org-id>"
echo ""
echo "4. Monitor instances:"
echo "   narthex-monitor"
echo ""
echo "5. View logs:"
echo "   narthex-org logs <org-id>"