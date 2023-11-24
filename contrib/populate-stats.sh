#!/bin/bash

# This script populates the stats table, to test the /monitor API.

usage() {
  echo "Usage: ./populate-stats.sh CONFIG_FILE"
  echo
  echo "Populates the stats table with random data"
}

# Detecting the help case.
if test "$1" = "--help" -o "$1" = "-h" -o -z ${1:-};
  then usage
  exit
fi
set -eu

DB_NAME=$(taler-config -c $1 -s libeufin-bankdb-postgres -o config)
echo Running on the database: $DB_NAME

# random number in range $1-$2
rnd () {
  shuf -i $1-$2 -n1
}

insert_stat () {
  echo "
    SET search_path TO libeufin_bank;
    CALL libeufin_bank.stats_register_payment (
      'taler_in'::text
      ,TO_TIMESTAMP($1)::timestamp
      ,($(rnd 0 99999999), $(rnd 0 99999999))::taler_amount
      ,null
    );
    CALL libeufin_bank.stats_register_payment (
      'taler_out'::text
      ,TO_TIMESTAMP($1)::timestamp
      ,($(rnd 0 99999999), $(rnd 0 99999999))::taler_amount
      ,null
    );
    CALL libeufin_bank.stats_register_payment (
      'cashin'::text
      ,TO_TIMESTAMP($1)::timestamp
      ,($(rnd 0 99999999), $(rnd 0 99999999))::taler_amount
      ,($(rnd 0 99999999), $(rnd 0 99999999))::taler_amount
    );
    CALL libeufin_bank.stats_register_payment (
      'cashout'::text
      ,TO_TIMESTAMP($1)::timestamp
      ,($(rnd 0 99999999), $(rnd 0 99999999))::taler_amount
      ,($(rnd 0 99999999), $(rnd 0 99999999))::taler_amount
    );"
}

# $1 == timestamp
insert_cmd () {
  echo "
    INSERT INTO libeufin_bank.bank_stats (
      timeframe
      ,start_time
      ,taler_in_count
      ,taler_in_volume
      ,taler_out_count
      ,taler_out_volume
      ,cashin_count
      ,cashin_regional_volume
      ,cashin_fiat_volume
      ,cashout_count
      ,cashout_regional_volume
      ,cashout_fiat_volume
      ) VALUES (
        'hour'
	,date_trunc('hour', TO_TIMESTAMP($1))
        ,$(rnd 1 3000)
        ,($(rnd 1 1000000), $(rnd 0 99999999))
        ,$(rnd 1 3000)
        ,($(rnd 1 1000000), $(rnd 0 99999999))
        ,$(rnd 1 3000)
        ,($(rnd 1 1000000), $(rnd 0 99999999))
        ,($(rnd 1 1000000), $(rnd 0 99999999))
        ,$(rnd 1 3000)
        ,($(rnd 1 1000000), $(rnd 0 99999999))
        ,($(rnd 1 1000000), $(rnd 0 99999999))
    );"
}
 
for n_hour_ago in `seq 1 100`; do
  echo -n .
  TIMESTAMP=$(date --date="${n_hour_ago} hour ago" +%s)
  # psql $DB_NAME -c "$(insert_cmd ${TIMESTAMP})" > /dev/null
  psql $DB_NAME -c "$(insert_stat ${TIMESTAMP})" > /dev/null
done
