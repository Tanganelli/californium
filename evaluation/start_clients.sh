#! /bin/bash

# start_clients.sh <NUMCLIENTS> <NUMNOTIFICATIONSPERCLIENT>

pmins=(20 50 20)
pmaxs=(100 70 30)
for i in $(eval echo {1..$1})
do
    echo "Client $i - pmin ${pmins[$i]} -pmax ${pmaxs[$i]}"
java -jar ../run/cf-coreinterface-client-1.0.0-SNAPSHOT.jar -l result$i.log -n $2 -pmax ${pmaxs[$i]} -pmin ${pmins[$i]} -u coap://127.0.0.1:5683/127.0.0.2:5683/CoREInterfaceResource -i 10.0.0.5 &
done
