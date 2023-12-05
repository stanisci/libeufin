# API Changes

This files contains all the API changes for the current release:

## bank serve

- POST /accounts: now returns RegisterAccountResponse with IBAN on http code 200 instead of 201
- CREATE /accounts: new debit_threshold field similar to the one of PATH /accounts
- GET /config: new default_debit_threshold field for the default debt limit for newly created accounts
- GET /config: new supported_tan_channels field which lists all the TAN channels supported by the server

## bank cli

## nexus
