# Makefile for Narthex
.PHONY: compile dist run package deploy deploy-no-restart bump-version set-version fpm-rpm fpm-build-rpm fpm-rpm-fuseki fpm-build-fuseki-rpm deploy-fuseki deploy-jena-tools deploy-fuseki-migrate fuseki-compact purge-org list-org editor-build editor-dev

NAME:=narthex
VERSION:=$(shell sh -c 'grep "ThisBuild / version" version.sbt | cut -d\" -f2')
MAINTAINER:="Sjoerd Siebinga <sjoerd@delving.eu>"
DESCRIPTION:="Narthex Aggregation and mapping platform."
FUSEKI:=apache-fuseki
FUSEKI_VERSION:=5.6.0
FUSEKI_VERSION_RPM:=5.6.0
JENA_VERSION:=5.6.0
FUSEKI_OLD_DIR:=/opt/hub3/fuseki
FUSEKI_DIR:=/opt/hub3/fuseki5
JENA_DIR:=/opt/hub3/jena
# Fuseki authentication (must match fuseki-username/fuseki-password in narthex.conf)
FUSEKI_USER:=admin
FUSEKI_PASS:=pw123

# Java 21 for better Metaspace management and Groovy 4.x compatibility
JAVA_HOME:=/usr/lib/jvm/java-21-openjdk
export JAVA_HOME
export PATH:=$(JAVA_HOME)/bin:$(PATH)

# var print rule
print-%  : ; @echo $* = $($*)

# Version bumping - increments the patch version (last number), commits, and tags
bump-version:
	@echo "Current version: $(VERSION)"
	@NEW_VERSION=$$(echo $(VERSION) | awk -F. '{print $$1"."$$2"."$$3"."$$4+1}') && \
	echo "New version: $$NEW_VERSION" && \
	sed -i 's/version := "$(VERSION)"/version := "'$$NEW_VERSION'"/' version.sbt && \
	sed -i 's/urlArgs: "v=$(VERSION)"/urlArgs: "v='$$NEW_VERSION'"/' app/assets/javascripts/main.js && \
	sed -i 's/v=[0-9]*\.[0-9]*\.[0-9]*\.[0-9]*/v='$$NEW_VERSION'/g' app/assets/javascripts/datasetList/main.js && \
	echo "Version bumped to $$NEW_VERSION in:" && \
	echo "  - version.sbt" && \
	echo "  - app/assets/javascripts/main.js" && \
	echo "  - app/assets/javascripts/datasetList/main.js" && \
	echo "" && \
	echo "Committing and tagging..." && \
	git add version.sbt app/assets/javascripts/main.js app/assets/javascripts/datasetList/main.js && \
	git commit -m "chore: bump version to $$NEW_VERSION" && \
	git tag -a "v$$NEW_VERSION" -m "Release $$NEW_VERSION" && \
	echo "" && \
	echo "Created git tag: v$$NEW_VERSION" && \
	echo "To push: git push && git push --tags"

# Set a specific version, commit, and tag
set-version:
	@if [ -z "$(V)" ]; then echo "Usage: make set-version V=0.8.2.99"; exit 1; fi
	@echo "Setting version to: $(V)"
	@sed -i 's/version := "[^"]*"/version := "$(V)"/' version.sbt
	@sed -i 's/urlArgs: "v=[^"]*"/urlArgs: "v=$(V)"/' app/assets/javascripts/main.js
	@sed -i 's/v=[0-9]*\.[0-9]*\.[0-9]*\.[0-9]*/v=$(V)/g' app/assets/javascripts/datasetList/main.js
	@echo "Version set to $(V)"
	@echo ""
	@echo "Committing and tagging..."
	@git add version.sbt app/assets/javascripts/main.js app/assets/javascripts/datasetList/main.js
	@git commit -m "chore: bump version to $(V)"
	@git tag -a "v$(V)" -m "Release $(V)"
	@echo ""
	@echo "Created git tag: v$(V)"
	@echo "To push: git push && git push --tags"

package:
	sbt package

compile:
	sbt compile

# Build the Svelte mapping editor
editor-build:
	@echo "=== Building Svelte Mapping Editor ==="
	cd editor && npm install && npm run build
	@echo "=== Editor built to public/editor/ ==="

# Run the Svelte editor in dev mode
editor-dev:
	cd editor && npm run dev

dist: editor-build
	@echo "=== Building Narthex distribution ==="
	sbt clean dist

run:
	sbt run

fpm-rpm:
	make dist
	unzip -d target/universal target/universal/narthex-$(VERSION).zip
	make fpm-build-rpm ARCH=amd64 FILE_ARCH=x86_64
	rpm --addsign *.rpm

fpm-build-rpm:
	fpm -s dir -t rpm -n $(NAME) -v $(VERSION) \
		--package $(NAME)-$(VERSION).$(FILE_ARCH).rpm \
		--force \
		-C . \
		--rpm-compression bzip2 --rpm-os linux \
		--url https://bitbucket.org/delving/$(NAME) \
		--description $(DESCRIPTION) \
		-m $(MAINTAINER) \
		--license "Apache 2.0" \
		-a $(ARCH) \
		target/universal/narthex-$(VERSION)/=/opt/hub3/narthex/NarthexVersion/


fpm-rpm-fuseki:
	rm -rf target/apache-jena-fuseki*
	wget -P target http://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz
	tar xvzf target/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz -C target
	make fpm-build-fuseki-rpm ARCH=amd64 FILE_ARCH=x86_64
	rpm --addsign *.rpm


fpm-build-fuseki-rpm:
	fpm -s dir -t rpm -n ${FUSEKI} -v $(FUSEKI_VERSION_RPM) \
		--package $(FUSEKI)-$(FUSEKI_VERSION_RPM).$(FILE_ARCH).rpm \
		--force \
		--depends java-21-openjdk \
		--rpm-compression bzip2 --rpm-os linux \
		--url https://jena.apache.org/documentation/fuseki2/index.html \
		--description "Apache Jena Fuseki" \
		-m $(MAINTAINER) \
		--license "Apache 2.0" \
		-a $(ARCH) \
		target/apache-jena-fuseki-${FUSEKI_VERSION}/=/opt/hub3/narthex/fuseki/

# Deploy to remote server
# Usage: make deploy SSH_HOST=root@server.example.com ORG_ID=brabantcloud
#
# Prerequisites:
#   - SSH key access to the server
#   - Server has /opt/hub3/{ORG_ID}/NarthexVersions/ directory
#   - Server has systemd service 'narthex' configured with 'current' symlink
#
# This will:
#   1. Build the distribution
#   2. Copy the zip to the server
#   3. Extract it
#   4. Update the 'current' symlink
#   5. Restart the narthex service
deploy: editor-build
	@if [ -z "$(SSH_HOST)" ]; then echo "Error: SSH_HOST is required. Usage: make deploy SSH_HOST=root@server.example.com ORG_ID=myorg"; exit 1; fi
	@if [ -z "$(ORG_ID)" ]; then echo "Error: ORG_ID is required. Usage: make deploy SSH_HOST=root@server.example.com ORG_ID=myorg"; exit 1; fi
	@echo "=== Deploying Narthex $(VERSION) to $(SSH_HOST) for org $(ORG_ID) ==="
	@echo ""
	@echo "Step 1: Building distribution..."
	sbt dist
	@echo ""
	@echo "Step 2: Copying to server..."
	scp target/universal/narthex-$(VERSION).zip $(SSH_HOST):/tmp/
	@echo ""
	@echo "Step 3: Extracting on server..."
	ssh $(SSH_HOST) "cd /opt/hub3/$(ORG_ID)/NarthexVersions && unzip -q -o /tmp/narthex-$(VERSION).zip && rm /tmp/narthex-$(VERSION).zip"
	@echo ""
	@echo "Step 4: Updating symlink..."
	ssh $(SSH_HOST) "cd /opt/hub3/$(ORG_ID)/NarthexVersions && ln -sfn narthex-$(VERSION) current"
	@echo ""
	@echo "Step 5: Restarting service..."
	ssh $(SSH_HOST) "systemctl restart narthex"
	@echo ""
	@echo "Step 6: Checking service status..."
	@sleep 3
	ssh $(SSH_HOST) "systemctl status narthex --no-pager | head -10"
	@echo ""
	@echo "=== Deployment complete: Narthex $(VERSION) is now running ==="

# Deploy without restart (useful for preparing update, then restart manually)
deploy-no-restart: editor-build
	@if [ -z "$(SSH_HOST)" ]; then echo "Error: SSH_HOST is required."; exit 1; fi
	@if [ -z "$(ORG_ID)" ]; then echo "Error: ORG_ID is required."; exit 1; fi
	@echo "=== Deploying Narthex $(VERSION) to $(SSH_HOST) (no restart) ==="
	sbt dist
	scp target/universal/narthex-$(VERSION).zip $(SSH_HOST):/tmp/
	ssh $(SSH_HOST) "cd /opt/hub3/$(ORG_ID)/NarthexVersions && unzip -q -o /tmp/narthex-$(VERSION).zip && rm /tmp/narthex-$(VERSION).zip"
	ssh $(SSH_HOST) "cd /opt/hub3/$(ORG_ID)/NarthexVersions && ln -sfn narthex-$(VERSION) current"
	@echo "=== Deployed. Run 'ssh $(SSH_HOST) systemctl restart narthex' to activate ==="

# Deploy Fuseki 5 to remote server (side by side with old Fuseki)
# Usage: make deploy-fuseki SSH_HOST=root@server.example.com
#
# This will:
#   1. Download Fuseki if not cached locally
#   2. Copy to server
#   3. Extract to /opt/hub3/fuseki5
#   4. Copy run/ directory from old fuseki
#   5. Does NOT restart - you need to update systemd and migrate databases first
deploy-fuseki:
	@if [ -z "$(SSH_HOST)" ]; then echo "Error: SSH_HOST is required. Usage: make deploy-fuseki SSH_HOST=root@server.example.com"; exit 1; fi
	@echo "=== Deploying Fuseki $(FUSEKI_VERSION) to $(SSH_HOST) ==="
	@echo ""
	@echo "Step 1: Downloading Fuseki $(FUSEKI_VERSION) if needed..."
	@mkdir -p target
	@if [ ! -f target/apache-jena-fuseki-$(FUSEKI_VERSION).tar.gz ]; then \
		wget -P target https://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-$(FUSEKI_VERSION).tar.gz; \
	else \
		echo "Using cached download"; \
	fi
	@echo ""
	@echo "Step 2: Copying to server..."
	scp target/apache-jena-fuseki-$(FUSEKI_VERSION).tar.gz $(SSH_HOST):/tmp/
	@echo ""
	@echo "Step 3: Extracting Fuseki to $(FUSEKI_DIR)..."
	ssh $(SSH_HOST) "cd /opt/hub3 && tar xzf /tmp/apache-jena-fuseki-$(FUSEKI_VERSION).tar.gz && rm -rf fuseki5 && mv apache-jena-fuseki-$(FUSEKI_VERSION) fuseki5 && rm /tmp/apache-jena-fuseki-$(FUSEKI_VERSION).tar.gz"
	@echo ""
	@echo "Step 4: Downloading Jena tools $(JENA_VERSION) if needed..."
	@if [ ! -f target/apache-jena-$(JENA_VERSION).tar.gz ]; then \
		wget -P target https://archive.apache.org/dist/jena/binaries/apache-jena-$(JENA_VERSION).tar.gz; \
	else \
		echo "Using cached Jena download"; \
	fi
	@echo ""
	@echo "Step 5: Copying Jena tools to server and extracting..."
	scp target/apache-jena-$(JENA_VERSION).tar.gz $(SSH_HOST):/tmp/
	ssh $(SSH_HOST) "cd /opt/hub3 && tar xzf /tmp/apache-jena-$(JENA_VERSION).tar.gz && rm -rf jena && mv apache-jena-$(JENA_VERSION) jena && rm /tmp/apache-jena-$(JENA_VERSION).tar.gz"
	@echo ""
	@echo "Step 6: Copying run/ directory from old Fuseki..."
	ssh $(SSH_HOST) "cp -r $(FUSEKI_OLD_DIR)/run $(FUSEKI_DIR)/"
	@echo ""
	@echo "Step 7: Configuring authentication (user: $(FUSEKI_USER))..."
	ssh $(SSH_HOST) 'echo "[main]" > $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "plainMatcher=org.apache.shiro.authc.credential.SimpleCredentialsMatcher" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "iniRealm.credentialsMatcher = \$$plainMatcher" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "[users]" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "$(FUSEKI_USER)=$(FUSEKI_PASS)" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "[urls]" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "/\$$/status = anon" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "/\$$/ping = anon" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "/\$$/** = authcBasic,user[$(FUSEKI_USER)]" >> $(FUSEKI_DIR)/run/shiro.ini'
	ssh $(SSH_HOST) 'echo "/** = authcBasic,user[$(FUSEKI_USER)]" >> $(FUSEKI_DIR)/run/shiro.ini'
	@echo ""
	@echo "Step 8: Setting permissions..."
	ssh $(SSH_HOST) "chown -R narthex:narthex $(FUSEKI_DIR) $(JENA_DIR)"
	@echo ""
	@echo "=== Fuseki $(FUSEKI_VERSION) and Jena tools installed ==="
	@echo ""
	@echo "Old Fuseki 2.4.1 still running at: $(FUSEKI_OLD_DIR)"
	@echo "New Fuseki $(FUSEKI_VERSION) ready at: $(FUSEKI_DIR)"
	@echo "Jena tools (tdbdump, tdb2.tdbloader) at: $(JENA_DIR)/bin/"
	@echo ""
	@echo "Next steps:"
	@echo "  1. Migrate databases to TDB2:"
	@echo "     make deploy-fuseki-migrate SSH_HOST=$(SSH_HOST) DATASET=brabantcloud"
	@echo ""
	@echo "  2. Update systemd to point to $(FUSEKI_DIR):"
	@echo "     ssh $(SSH_HOST) 'sed -i \"s|$(FUSEKI_OLD_DIR)|$(FUSEKI_DIR)|g\" /usr/lib/systemd/system/fuseki.service'"
	@echo "     ssh $(SSH_HOST) systemctl daemon-reload"
	@echo ""
	@echo "  3. Ensure narthex.conf has matching credentials:"
	@echo "     fuseki-username = \"$(FUSEKI_USER)\""
	@echo "     fuseki-password = \"$(FUSEKI_PASS)\""
	@echo ""
	@echo "  4. Switch over:"
	@echo "     ssh $(SSH_HOST) systemctl restart fuseki"

# Deploy only Jena tools (tdbdump, tdb2.tdbloader, etc.)
# Usage: make deploy-jena-tools SSH_HOST=root@server.example.com
deploy-jena-tools:
	@if [ -z "$(SSH_HOST)" ]; then echo "Error: SSH_HOST is required. Usage: make deploy-jena-tools SSH_HOST=root@server.example.com"; exit 1; fi
	@echo "=== Deploying Jena tools $(JENA_VERSION) to $(SSH_HOST) ==="
	@echo ""
	@echo "Step 1: Downloading Jena $(JENA_VERSION) if needed..."
	@mkdir -p target
	@if [ ! -f target/apache-jena-$(JENA_VERSION).tar.gz ]; then \
		wget -P target https://archive.apache.org/dist/jena/binaries/apache-jena-$(JENA_VERSION).tar.gz; \
	else \
		echo "Using cached download"; \
	fi
	@echo ""
	@echo "Step 2: Copying to server..."
	scp target/apache-jena-$(JENA_VERSION).tar.gz $(SSH_HOST):/tmp/
	@echo ""
	@echo "Step 3: Extracting to $(JENA_DIR)..."
	ssh $(SSH_HOST) "cd /opt/hub3 && tar xzf /tmp/apache-jena-$(JENA_VERSION).tar.gz && rm -rf jena && mv apache-jena-$(JENA_VERSION) jena && rm /tmp/apache-jena-$(JENA_VERSION).tar.gz"
	@echo ""
	@echo "Step 4: Setting permissions..."
	ssh $(SSH_HOST) "chown -R narthex:narthex $(JENA_DIR)"
	@echo ""
	@echo "=== Jena tools $(JENA_VERSION) installed to $(JENA_DIR) ==="
	@echo ""
	@echo "Available tools:"
	@echo "  $(JENA_DIR)/bin/tdbdump       - Export TDB1 database"
	@echo "  $(JENA_DIR)/bin/tdb2.tdbloader - Load data into TDB2"
	@echo "  $(JENA_DIR)/bin/tdb2.tdbcompact - Compact TDB2 database"

# Full Fuseki migration with TDB1 to TDB2 conversion
# Usage: make deploy-fuseki-migrate SSH_HOST=root@server.example.com DATASET=brabantcloud
#
# Prerequisites:
#   - Run deploy-fuseki first to install Fuseki 5 to /opt/hub3/fuseki5
#   - Run deploy-jena-tools to install Jena tools to /opt/hub3/jena
#   - Source TDB1 databases should be in /opt/hub3/fuseki5/run/databases_v2/
deploy-fuseki-migrate:
	@if [ -z "$(SSH_HOST)" ]; then echo "Error: SSH_HOST is required."; exit 1; fi
	@if [ -z "$(DATASET)" ]; then echo "Error: DATASET is required. Usage: make deploy-fuseki-migrate SSH_HOST=... DATASET=brabantcloud"; exit 1; fi
	@echo "=== Migrating $(DATASET) from TDB1 to TDB2 on $(SSH_HOST) ==="
	@echo ""
	@echo "Source (TDB1): $(FUSEKI_DIR)/run/databases_v2/$(DATASET)"
	@echo "Target (TDB2): $(FUSEKI_DIR)/run/databases/$(DATASET)"
	@echo ""
	@echo "Step 1: Exporting $(DATASET) from TDB1 (this may take a while)..."
	ssh $(SSH_HOST) "$(JENA_DIR)/bin/tdbdump --loc $(FUSEKI_DIR)/run/databases_v2/$(DATASET) > /tmp/$(DATASET).nq"
	ssh $(SSH_HOST) "ls -lh /tmp/$(DATASET).nq"
	@echo ""
	@echo "Step 2: Creating TDB2 database (this may take a while)..."
	ssh $(SSH_HOST) "rm -rf $(FUSEKI_DIR)/run/databases/$(DATASET)"
	ssh $(SSH_HOST) "mkdir -p $(FUSEKI_DIR)/run/databases/$(DATASET)"
	ssh $(SSH_HOST) "$(JENA_DIR)/bin/tdb2.tdbloader --loc $(FUSEKI_DIR)/run/databases/$(DATASET) /tmp/$(DATASET).nq"
	@echo ""
	@echo "Step 3: Updating config to TDB2 format..."
	ssh $(SSH_HOST) "sed -i 's|tdb:DatasetTDB|tdb2:DatasetTDB2|g; s|tdb:location|tdb2:location|g; s|tdb:unionDefaultGraph|tdb2:unionDefaultGraph|g; s|http://jena.hpl.hp.com/2008/tdb#|http://jena.apache.org/2016/tdb#|g' $(FUSEKI_DIR)/run/configuration/$(DATASET).ttl"
	@echo ""
	@echo "Step 4: Cleaning up export file..."
	ssh $(SSH_HOST) "rm /tmp/$(DATASET).nq"
	@echo ""
	@echo "Step 5: Setting permissions..."
	ssh $(SSH_HOST) "chown -R narthex:narthex $(FUSEKI_DIR)/run/databases/$(DATASET)"
	@echo ""
	@echo "Step 6: Showing new database size..."
	ssh $(SSH_HOST) "du -sh $(FUSEKI_DIR)/run/databases/$(DATASET)"
	@echo ""
	@echo "=== Migration complete for $(DATASET)! ==="
	@echo ""
	@echo "To switch to Fuseki 5, update systemd and restart:"
	@echo "  ssh $(SSH_HOST) 'sed -i \"s|$(FUSEKI_OLD_DIR)|$(FUSEKI_DIR)|g\" /usr/lib/systemd/system/fuseki.service'"
	@echo "  ssh $(SSH_HOST) systemctl daemon-reload"
	@echo "  ssh $(SSH_HOST) systemctl start fuseki"
	@echo "  ssh $(SSH_HOST) systemctl start narthex"

# Trigger compaction on a remote Fuseki TDB2 dataset
# Usage: make fuseki-compact SSH_HOST=root@server.example.com DATASET=brabantcloud
fuseki-compact:
	@if [ -z "$(SSH_HOST)" ]; then echo "Error: SSH_HOST is required."; exit 1; fi
	@if [ -z "$(DATASET)" ]; then echo "Error: DATASET is required. Usage: make fuseki-compact SSH_HOST=... DATASET=brabantcloud"; exit 1; fi
	@echo "=== Triggering compaction for $(DATASET) on $(SSH_HOST) ==="
	ssh $(SSH_HOST) "curl -s -X POST 'http://localhost:3030/\$$/compact/$(DATASET)?deleteOld=true'"
	@echo ""
	@echo "Compaction triggered. Check status with:"
	@echo "  ssh $(SSH_HOST) curl -s http://localhost:3030/\$$/tasks"

# Purge all data for an organization (local development)
# Usage: make purge-org ORG_ID=brabantcloud
#
# This will:
#   1. Delete all dataset directories from NarthexFiles
#   2. Delete all SIP files
#   3. Delete all raw uploaded files
#   4. Clear the processing queue
#   5. Drop all graphs from local Fuseki
#
# WARNING: This is destructive and cannot be undone!
NARTHEX_FILES:=$(HOME)/NarthexFiles
FUSEKI_URL:=http://127.0.0.1:3033
# Fuseki auth - override with: make purge-org ORG_ID=x FUSEKI_USER=admin FUSEKI_PASS=secret
FUSEKI_USER?=admin
FUSEKI_PASS?=pw123

purge-org:
	@if [ -z "$(ORG_ID)" ]; then echo "Error: ORG_ID is required. Usage: make purge-org ORG_ID=brabantcloud"; exit 1; fi
	@if [ ! -d "$(NARTHEX_FILES)/$(ORG_ID)" ]; then echo "Error: $(NARTHEX_FILES)/$(ORG_ID) does not exist"; exit 1; fi
	@echo ""
	@echo "=== PURGE ORGANIZATION DATA: $(ORG_ID) ==="
	@echo ""
	@echo "This will DELETE all data for organization '$(ORG_ID)':"
	@echo "  - $(NARTHEX_FILES)/$(ORG_ID)/datasets/*"
	@echo "  - $(NARTHEX_FILES)/$(ORG_ID)/sips/*"
	@echo "  - $(NARTHEX_FILES)/$(ORG_ID)/raw/*"
	@echo "  - $(NARTHEX_FILES)/$(ORG_ID)/queue-state.json"
	@echo "  - All graphs in Fuseki for $(ORG_ID)"
	@echo ""
	@echo "Current datasets:"
	@ls -1 $(NARTHEX_FILES)/$(ORG_ID)/datasets/ 2>/dev/null || echo "  (none)"
	@echo ""
	@read -p "Are you sure you want to delete all this data? [y/N] " confirm && [ "$$confirm" = "y" ] || (echo "Aborted."; exit 1)
	@echo ""
	@echo "Step 1: Clearing Fuseki graphs..."
	@curl -s -u $(FUSEKI_USER):$(FUSEKI_PASS) -X POST "$(FUSEKI_URL)/$(ORG_ID)/update" \
		-H "Content-Type: application/sparql-update" \
		--data "DROP ALL" && echo "  Fuseki graphs dropped" || echo "  Warning: Could not connect to Fuseki (may not be running)"
	@echo ""
	@echo "Step 2: Removing dataset directories..."
	rm -rf $(NARTHEX_FILES)/$(ORG_ID)/datasets/*
	@echo "  Datasets removed"
	@echo ""
	@echo "Step 3: Removing SIP files..."
	rm -rf $(NARTHEX_FILES)/$(ORG_ID)/sips/*
	@echo "  SIPs removed"
	@echo ""
	@echo "Step 4: Removing raw uploads..."
	rm -rf $(NARTHEX_FILES)/$(ORG_ID)/raw/*
	@echo "  Raw files removed"
	@echo ""
	@echo "Step 5: Clearing queue state..."
	rm -f $(NARTHEX_FILES)/$(ORG_ID)/queue-state.json
	@echo "  Queue cleared"
	@echo ""
	@echo "=== Purge complete for $(ORG_ID) ==="
	@echo ""
	@echo "Note: Restart Narthex to ensure clean state: sbt run"

# List datasets for an organization (useful before purging)
# Usage: make list-org ORG_ID=brabantcloud
list-org:
	@if [ -z "$(ORG_ID)" ]; then echo "Error: ORG_ID is required. Usage: make list-org ORG_ID=brabantcloud"; exit 1; fi
	@echo ""
	@echo "=== Organization: $(ORG_ID) ==="
	@echo ""
	@echo "Datasets:"
	@ls -1 $(NARTHEX_FILES)/$(ORG_ID)/datasets/ 2>/dev/null || echo "  (none)"
	@echo ""
	@echo "Dataset sizes:"
	@du -sh $(NARTHEX_FILES)/$(ORG_ID)/datasets/*/ 2>/dev/null || echo "  (none)"
	@echo ""
	@echo "SIP files:"
	@ls -1 $(NARTHEX_FILES)/$(ORG_ID)/sips/ 2>/dev/null || echo "  (none)"
	@echo ""
	@echo "Raw uploads:"
	@ls -1 $(NARTHEX_FILES)/$(ORG_ID)/raw/ 2>/dev/null || echo "  (none)"
