#!/bin/bash
set -evu

apt-get update
apt-get upgrade -yqq

./bootstrap
./configure --prefix /usr
make build

sudo -u postgres /usr/lib/postgresql/15/bin/postgres -D /etc/postgresql/15/main -h localhost -p 5432 &
sleep 10
sudo -u postgres createuser -p 5432 root
sudo -u postgres createdb -p 5432 -O root libeufincheck

check_command()
{
	PGPORT=5432 make check &> test-suite.log
}

if ! check_command ; then
    cat test-suite.log
    exit 1
fi
