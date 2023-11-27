#!/bin/bash

# This script populates the stats table, to test the /monitor API.

usage() {
  echo "Usage: ./populate-stats.sh CONFIG_FILE [--one]"
  echo
  echo "Populates the stats table with random data"
  echo
  echo Parameters:
  echo
  echo --one instead of random amounts, it always uses 1.0
}

# Detecting the help case.
if test "$1" = "--help" -o "$1" = "-h" -o -z ${1:-};
  then usage
  exit
fi

HAS_ONE=0
if test "$2" = "--one";
  then HAS_ONE=1
fi

set -eu
DB_NAME=$(taler-config -c $1 -s libeufin-bankdb-postgres -o config)
echo Running on the database: $DB_NAME

# random number in range $1-$2
rnd () {
  shuf -i $1-$2 -n1
}

insert_stat_one () {
  echo "
    SET search_path TO libeufin_bank;
    CALL libeufin_bank.stats_register_payment (
      'taler_in'::text
      ,TO_TIMESTAMP($1)::timestamp
      ,(1, 0)::taler_amount
      ,null
    );
    CALL libeufin_bank.stats_register_payment (
      'taler_out'::text
      ,TO_TIMESTAMP($1)::timestamp
      ,(1, 0)::taler_amount
      ,null
    );
    CALL libeufin_bank.stats_register_payment (
      'cashin'::text
      ,TO_TIMESTAMP($1)::timestamp
      ,(1, 0)::taler_amount
      ,(1, 0)::taler_amount
    );
    CALL libeufin_bank.stats_register_payment (
      'cashout'::text
      ,TO_TIMESTAMP($1)::timestamp
      ,(1, 0)::taler_amount
      ,(1, 0)::taler_amount
    );"
}

insert_stat_rand () {
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

for n_hour_ago in `seq 1 100`; do
  echo -n .
  TIMESTAMP=$(date --date="${n_hour_ago} hour ago" +%s)
  if test $HAS_ONE = 1; then
    psql $DB_NAME -c "$(insert_stat_one ${TIMESTAMP})" > /dev/null
  else
    psql $DB_NAME -c "$(insert_stat_rand ${TIMESTAMP})" > /dev/null
  fi
done
