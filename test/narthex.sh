#!/bin/bash
CMD=$1
ORG_ID=$2

usage() {
	echo "Usage: narthex start/stop brabantcloud/norvegiana/dimcon/frysk-kulturhus/nasjonalmuseet/culturebrokers/nave"
	exit 1
}

case "$ORG_ID" in

	brabantcloud)
		PORT=9001
		;;

	norvegiana)
		PORT=9004
		;;

	dimcon)
		PORT=9005
		;;

	frysk-kulturhus)
		PORT=9006
		;;

	nasjonalmuseet)
		PORT=9007
		;;

	culturebrokers)
		PORT=9008
		;;

	nave)
		PORT=9010
		;;

	*)
		usage
		;;
esac

PID=$HOME/NarthexLogs/$ORG_ID.pid
EXE=$HOME/narthex/bin/narthex
LOGGER=$HOME/NarthexConfig/logger.xml
CONF=$HOME/NarthexConfig/$ORG_ID.conf
LOG=$HOME/NarthexLogs/$ORG_ID-application.log
STDOUT=$HOME/NarthexLogs/$ORG_ID-stdout.log

case "$CMD" in

	start)
		echo "Starting Narthex for organization '$ORG_ID' on port '$PORT'"
		$EXE -J-server -Dapplication.log=$LOG -Dlogger.file=$LOGGER -Dpidfile.path=$PID -Dhttp.port=$PORT -Dconfig.file=$CONF >> $STDOUT &
		;;

	stop)
		echo "Stopping $ORG_ID Narthex"
		echo kill $(cat $HOME/NarthexLogs/$ORG_ID.pid)
		;;

	*)
		usage
		;;
esac

