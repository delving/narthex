#!/bin/bash

# be really strict about failures
set -e

# install useki
FUSEKI_VERSION="2.3.0"

echo "Settup up Narthex"

# cp develop configuration
cd /home/vagrant/NarthexVersions/narthex-dev/
cp provisioning/templates/narthex.conf development.conf 

# setup basedirs for narthex
mkdir -p /home/vagrant/NarthexFiles
cd /home/vagrant/NarthexFiles

# add the default EDM files
mkdir -p vagrant/factory/edm
cd vagrant/factory/edm
rm -rf *.xml *.xsd
wget https://raw.githubusercontent.com/delving/schemas.delving.eu/master/edm/edm_5.2.6_record-definition.xml
wget https://raw.githubusercontent.com/delving/schemas.delving.eu/master/edm/edm_5.2.6_validation.xsd

echo "Done setting up narthex"

# installing java8
sudo apt-get install software-properties-common debconf-utils python-software-properties -y -q
sudo apt-add-repository ppa:webupd8team/java -y
sudo apt-get update -y -q
echo "oracle-java8-installer shared/accepted-oracle-license-v1-1 select true" | sudo debconf-set-selections
sudo apt-get install oracle-java8-installer -y -q


# installing SBT and java dependencies

sudo apt-get remove scala-library scala -y -q
sudo wget www.scala-lang.org/files/archive/scala-2.10.4.deb
set +e
sudo dpkg -i scala-2.10.4.deb
sudo apt-get install -f -y -q
sudo apt-get update
sudo apt-get install scala -y -q
set -e
sudo wget https://dl.bintray.com/sbt/debian/sbt-0.13.8.deb
sudo dpkg -i sbt-0.13.8.deb
sudo apt-get update -y -q
sudo apt-get install sbt -y -q


echo "Installing Fuseki"

sudo mkdir -p /opt
sudo rm -rf /opt/fuseki
cd /opt
sudo wget -q http://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-$FUSEKI_VERSION.tar.gz
sudo tar xvzf apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz
sudo mkdir -p /opt/fuseki/run/configuration/
sudo mv apache-jena-fuseki-${FUSEKI_VERSION}/* /opt/fuseki/
# sudo rm -rf /etc/default/fuseki
# copy configuration templatesj
sudo cp /home/vagrant/NarthexVersions/narthex-dev/provisioning/templates/narthex_prod.ttl /opt/fuseki/run/configuration/
echo "JAVA=/usr/lib/jvm/java-8-oracle/jre/bin/java" | sudo tee /etc/default/fuseki > /dev/null
echo "FUSEKI_BASE=/opt/fuseki/run" | sudo tee -a /etc/default/fuseki > /dev/null
echo "FUSEKI_HOME=/opt/fuseki" | sudo tee -a /etc/default/fuseki > /dev/null

sudo cp /home/vagrant/NarthexVersions/narthex-dev/provisioning/templates/fuseki_init.sh  /etc/init.d/fuseki
sudo chmod +x /etc/init.d/fuseki
sudo update-rc.d fuseki defaults 95 10
sudo /etc/init.d/fuseki restart

echo "Done installing fuseki. "

echo "Getting all the sbt dependencies ..."

cd /home/vagrant/NarthexVersions/narthex-dev/
sbt build

echo "Done building the Narthex project for the first time. "


echo "To run narthex go to /home/vagrant/NarthexVersions/narthex-dev/ and execute 'sbt run'"

echo "Then you can find narthex on http://narthex.localhost:9000"
