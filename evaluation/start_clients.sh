#! /bin/bash

# start_clients.sh <NUMCLIENTS> <NUMNOTIFICATIONSPERCLIENT>
for i in $(eval echo {1..$1})
do
	java -jar ../run/cf-coreinterface-client-1.0.0-SNAPSHOT.jar -l result$i.log -n $2 -pmax 30 -pmin 20 -u coap://127.0.0.1:5683/127.0.0.2:5683/CoREInterfaceResource &
done
