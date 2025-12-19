#!/bin/bash
ADDR=${1:-localhost}
PORT=${1:-80}

echo "тест прокси $ADDR:$PORT с 5-ю параллельными клиентами, 50МБ"

for i in {1..5}; do 
    wget --no-http-keep-alive \
         http://ccfit.nsu.ru/~rzheutskiy/test_files/50mb.dat \
         -e use_proxy=on \
         -e http_proxy=$ADDR:$PORT \
         -O /dev/null \
         && date & 
done

wait
echo "тест закончился, ура"
