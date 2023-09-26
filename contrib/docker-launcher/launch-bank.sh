#!/bin/bash

service postgresql start
sudo -u postgres createuser -s root
createdb libeufinbank
libeufin-bank dbinit -c /libeufin-bank.conf
libeufin-bank serve -c /libeufin-bank.conf
