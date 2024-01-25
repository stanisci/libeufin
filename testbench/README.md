# LibEuFin Test Bench

## Interactive EBICS test

To add a platform write a minimal configuration file at `testbench/test/PLATFORM/ebics.conf` such as :


``` ini
[nexus-ebics]
currency = CHF

# Bank
HOST_BASE_URL = https://isotest.postfinance.ch/ebicsweb/ebicsweb
BANK_DIALECT = postfinance

# EBICS IDs
HOST_ID = PFEBICS
USER_ID = PFC00563
PARTNER_ID = PFC00563

IBAN = CH7789144474425692816
```

To start the interactive EBICS test run :

``` sh
make testbench platform=PLATFORM
```

If HOST_BASE_URL is one a known test platform we will generate and then offer to reset client private keys to test keys registration, otherwise, we will expect existing keys to be found at `testbench/test/PLATFORM/ebics.edited.conf`.

This minimal configuration will be augmented on start, you can find the full documentation at `testbench/test/PLATFORM/client-ebics-keys.json`.