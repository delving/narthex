# Vagrant Development

Most core development on narthex takes place using Vagrant. The following
steps are required to get up and running.

    * Install Vagrant from the vagrantup.com website.
    * Go to the root directory of the project
    * Edit the development.conf file where applicable
    * Run `vagrant up`
    * Run `vagrant ssh`
    * navigate to NarthexVersions/narthex_dev/
    * Run `sbt run`
    * On your development machine go to http://narthex.localhost:9000
    
If you want to run in debug mode so that you can stop code from your IDE
of choice, you have to run the following command instead of `sbt run`: 

    * SBT_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005" && sbt run

