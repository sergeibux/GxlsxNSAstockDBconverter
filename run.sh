#!/bin/bash

ts=`date +%s`
changesFnam="/tmp/changed_records.nsa.$ts"
confFname="/etc/NSA/GxlsxNSAstockDB_converter.conf"

mv /tmp/changed_records.nsa $changesFnam

if [ $# -eq 1 ]; then
  if [ $1 = "compile" ]; then
    mvn package && java -jar target/GxlsxNSAstockDBconverter-1.0-SNAPSHOT.jar $changesFnam
    exit
  fi
fi

java -jar target/GxlsxNSAstockDBconverter-1.0-SNAPSHOT.jar $changesFnam

#if did not works...
mv $changesFnam /tmp/changed_records.nsa
