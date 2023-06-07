#!/bin/bash

curl -v -s \
  -XPOST \
  -H "Content-Type: application/json" \
  -d'{"amount": "MANA:3.00", "reservePub": "re:publica"}' \
  http://localhost:5001/facades/test-facade/taler-wire-gateway/admin/add-incoming
