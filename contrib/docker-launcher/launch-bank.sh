#!/bin/bash

service postgresql start
sudo -u postgres createuser -s root
createdb libeufinbank
libeufin-bank dbinit
libeufin-bank serve
