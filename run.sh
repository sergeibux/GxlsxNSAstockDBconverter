#!/bin/bash

ts=`date +%s`
filename="/tmp/changed_records.nsa.$ts"
mv /tmp/changed_records.nsa $filename

if [ $# -eq 1 ]; then
  if [ $1 = "compile" ]; then
    mvn package && java -jar target/GxlsxNSAstockDBconverter-1.0-SNAPSHOT.jar $filename
    exit
  fi
fi

java -jar target/GxlsxNSAstockDBconverter-1.0-SNAPSHOT.jar $filename

#TODO for tests...
# rm $filename
mv $filename /tmp/changed_records.nsa
