# API Changes

This files contains all the API changes for the current release:

## bank serve

- POST /accounts: now returns RegisterAccountResponse with IBAN on http code 200 instead of 201
- CREATE /accounts: new debit_threshold field similar to the one of PATH /accounts
- GET /config: new default_debit_threshold field for the default debt limit for newly created accounts
- GET /config: new supported_tan_channels field which lists all the TAN channels supported by the server
- GET /config: new allow_edit_name and allow_edit_cashout_payto_uri fields for path authorisation
- POST /accounts: rename challenge_contact_data to contact_data and internal_payto_uri to payto_uri
- PATCH /accounts/USERNAME: add is_public, remove is_taler_exchange and rename challenge_contact_data to contact_data
- GET /accounts: add payto_uri, is_public and is_taler_exchange
- GET /accounts/USERNAME: add is_public and is_taler_exchange
- GET /public-accounts: add is_taler_exchange and rename account_name to username

## bank cli

## nexus
