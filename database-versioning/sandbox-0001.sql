-- Compatible with LibEuFin version: 1fe2687aaf696c8566367fe7ed082f1d78e6b78d

BEGIN;

SELECT _v.register_patch('sandbox-0001', NULL, NULL);

CREATE TABLE IF NOT EXISTS demobankconfigs 
  (id BIGSERIAL PRIMARY KEY
  ,hostname TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS bankaccounts 
  (id SERIAL PRIMARY KEY
  ,iban TEXT NOT NULL
  ,bic TEXT DEFAULT 'SANDBOXX' NOT NULL
  ,"label" TEXT NOT NULL
  ,"owner" TEXT NOT NULL
  ,"isPublic" BOOLEAN DEFAULT false NOT NULL
  ,"demoBank" BIGINT NOT NULL
  ,"lastTransaction" BIGINT NULL
  ,"lastFiatSubmission" BIGINT NULL
  ,"lastFiatFetch" TEXT DEFAULT '0' NOT NULL
  );

ALTER TABLE
  bankaccounts ADD CONSTRAINT accountLabelIndex UNIQUE ("label");

CREATE TABLE IF NOT EXISTS bankaccounttransactions 
  (id BIGSERIAL PRIMARY KEY
  ,"creditorIban" TEXT NOT NULL
  ,"creditorBic" TEXT NULL
  ,"creditorName" TEXT NOT NULL
  ,"debtorIban" TEXT NOT NULL
  ,"debtorBic" TEXT NULL
  ,"debtorName" TEXT NOT NULL
  ,subject TEXT NOT NULL
  ,amount TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,"date" BIGINT NOT NULL
  ,"accountServicerReference" TEXT NOT NULL
  ,"pmtInfId" TEXT NULL
  ,"EndToEndId" TEXT NULL
  ,direction TEXT NOT NULL
  ,account INT NOT NULL
  ,demobank BIGINT NOT NULL
  );

CREATE TABLE IF NOT EXISTS cashoutsubmissions 
  (id BIGSERIAL PRIMARY KEY
  ,"localTransaction" BIGINT NOT NULL
  ,"maybeNexusResponse" TEXT NULL
  ,"submissionTime" BIGINT NULL
  );

ALTER TABLE
  cashoutsubmissions ADD CONSTRAINT cashoutsubmissions_localtransaction_unique UNIQUE ("localTransaction");

CREATE TABLE IF NOT EXISTS demobankconfigpairs 
  (id BIGSERIAL PRIMARY KEY
  ,"demobankName" TEXT NOT NULL
  ,"configKey" TEXT NOT NULL
  ,"configValue" TEXT NULL
  );

CREATE TABLE IF NOT EXISTS ebicssubscribers 
  (id SERIAL PRIMARY KEY
  ,"userID" TEXT NOT NULL
  ,"partnerID" TEXT NOT NULL
  ,"systemID" TEXT NULL
  ,"hostID" TEXT NOT NULL
  ,"signatureKey" INT NULL
  ,"encryptionKey" INT NULL
  ,"authorizationKey" INT NULL
  ,"nextOrderID" INT NOT NULL
  ,"state" INT NOT NULL
  ,"bankAccount" INT NULL
  );

CREATE TABLE IF NOT EXISTS ebicssubscriberpublickeys
  (id SERIAL PRIMARY KEY
   ,"rsaPublicKey" bytea NOT NULL
   ,"state" INT NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebicshosts 
  (id SERIAL PRIMARY KEY
  ,"hostID" TEXT NOT NULL
  ,"ebicsVersion" TEXT NOT NULL
  ,"signaturePrivateKey" bytea NOT NULL
  ,"encryptionPrivateKey" bytea NOT NULL
  ,"authenticationPrivateKey" bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebicsdownloadtransactions 
  ("transactionID" TEXT NOT NULL
  ,"orderType" TEXT NOT NULL
  ,host INT NOT NULL
  ,subscriber INT NOT NULL
  ,"encodedResponse" TEXT NOT NULL
  ,"transactionKeyEnc" bytea NOT NULL
  ,"numSegments" INT NOT NULL
  ,"segmentSize" INT NOT NULL
  ,"receiptReceived" BOOLEAN NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebicsuploadtransactions 
  ("transactionID" TEXT NOT NULL
  ,"orderType" TEXT NOT NULL
  ,"orderID" TEXT NOT NULL
  ,host INT NOT NULL
  ,subscriber INT NOT NULL
  ,"numSegments" INT NOT NULL
  ,"lastSeenSegment" INT NOT NULL
  ,"transactionKeyEnc" bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebicsuploadtransactionchunks 
  ("transactionID" TEXT NOT NULL
  ,"chunkIndex" INT NOT NULL
  ,"chunkContent" bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebicsordersignatures 
  (id SERIAL PRIMARY KEY
  ,"orderID" TEXT NOT NULL
  ,"orderType" TEXT NOT NULL
  ,"partnerID" TEXT NOT NULL
  ,"userID" TEXT NOT NULL
  ,"signatureAlgorithm" TEXT NOT NULL
  ,"signatureValue" bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS bankaccountfreshtransactions 
  (id BIGSERIAL PRIMARY KEY
  ,"transaction" BIGINT NOT NULL
  );

CREATE TABLE IF NOT EXISTS bankaccountreports 
  (id SERIAL PRIMARY KEY
  ,"reportId" TEXT NOT NULL
  ,"creationTime" BIGINT NOT NULL
  ,"xmlMessage" TEXT NOT NULL
  ,"bankAccount" INT NOT NULL
  );

CREATE TABLE IF NOT EXISTS bankaccountstatements 
  (id SERIAL PRIMARY KEY
  ,"statementId" TEXT NOT NULL
  ,"creationTime" BIGINT NOT NULL
  ,"xmlMessage" TEXT NOT NULL
  ,"bankAccount" INT NOT NULL
  ,"balanceClbd" TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS talerwithdrawals 
  (id BIGSERIAL PRIMARY KEY
  ,wopid uuid NOT NULL
  ,amount TEXT NOT NULL
  ,"selectionDone" BOOLEAN DEFAULT false NOT NULL
  ,aborted BOOLEAN DEFAULT false NOT NULL
  ,"confirmationDone" BOOLEAN DEFAULT false NOT NULL
  ,"reservePub" TEXT NULL
  ,"selectedExchangePayto" TEXT NULL
  ,"walletBankAccount" INT NOT NULL
  );

CREATE TABLE IF NOT EXISTS demobankcustomers 
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,"passwordHash" TEXT NOT NULL
  ,"name" TEXT NULL
  ,email TEXT NULL
  ,phone TEXT NULL
  ,cashout_address TEXT NULL
  );

CREATE TABLE IF NOT EXISTS cashoutoperations 
  (id BIGSERIAL PRIMARY KEY
  ,uuid uuid NOT NULL
  ,"amountDebit" TEXT NOT NULL
  ,"amountCredit" TEXT NOT NULL
  ,"buyAtRatio" TEXT NOT NULL
  ,"buyInFee" TEXT NOT NULL
  ,"sellAtRatio" TEXT NOT NULL
  ,"sellOutFee" TEXT NOT NULL
  ,subject TEXT NOT NULL
  ,"creationTime" BIGINT NOT NULL
  ,"confirmationTime" BIGINT NULL
  ,"tanChannel" INT NOT NULL
  ,account TEXT NOT NULL
  ,"cashoutAddress" TEXT NOT NULL
  ,tan TEXT NOT NULL
  ,status INT DEFAULT 1 NOT NULL
  );

ALTER TABLE
  bankaccounts ADD CONSTRAINT fk_bankaccounts_demobank_id FOREIGN KEY ("demoBank") REFERENCES demobankconfigs(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  bankaccounts ADD CONSTRAINT fk_bankaccounts_lasttransaction_id FOREIGN KEY ("lastTransaction") REFERENCES bankaccounttransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  bankaccounts ADD CONSTRAINT fk_bankaccounts_lastfiatsubmission_id FOREIGN KEY ("lastFiatSubmission") REFERENCES bankaccounttransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  bankaccounttransactions ADD CONSTRAINT fk_bankaccounttransactions_account_id FOREIGN KEY (account) REFERENCES bankaccounts(id) ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE
  bankaccounttransactions ADD CONSTRAINT fk_bankaccounttransactions_demobank_id FOREIGN KEY (demobank) REFERENCES demobankconfigs(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  cashoutsubmissions ADD CONSTRAINT fk_cashoutsubmissions_localtransaction_id FOREIGN KEY ("localTransaction") REFERENCES bankaccounttransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  ebicssubscribers ADD CONSTRAINT fk_ebicssubscribers_signaturekey_id FOREIGN KEY ("signatureKey") REFERENCES ebicssubscriberpublickeys(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  ebicssubscribers ADD CONSTRAINT fk_ebicssubscribers_encryptionkey_id FOREIGN KEY ("encryptionKey") REFERENCES ebicssubscriberpublickeys(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  ebicssubscribers ADD CONSTRAINT fk_ebicssubscribers_authorizationkey_id FOREIGN KEY ("authorizationKey") REFERENCES ebicssubscriberpublickeys(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  ebicssubscribers ADD CONSTRAINT fk_ebicssubscribers_bankaccount_id FOREIGN KEY ("bankAccount") REFERENCES bankaccounts(id) ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE
  ebicsdownloadtransactions ADD CONSTRAINT fk_ebicsdownloadtransactions_host_id FOREIGN KEY (host) REFERENCES ebicshosts(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  ebicsdownloadtransactions ADD CONSTRAINT fk_ebicsdownloadtransactions_subscriber_id FOREIGN KEY (subscriber) REFERENCES ebicssubscribers(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  ebicsuploadtransactions ADD CONSTRAINT fk_ebicsuploadtransactions_host_id FOREIGN KEY (host) REFERENCES ebicshosts(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  ebicsuploadtransactions ADD CONSTRAINT fk_ebicsuploadtransactions_subscriber_id FOREIGN KEY (subscriber) REFERENCES ebicssubscribers(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  bankaccountfreshtransactions ADD CONSTRAINT fk_bankaccountfreshtransactions_transaction_id FOREIGN KEY ("transaction") REFERENCES bankaccounttransactions(id) ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE
  bankaccountreports ADD CONSTRAINT fk_bankaccountreports_bankaccount_id FOREIGN KEY ("bankAccount") REFERENCES bankaccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  bankaccountstatements ADD CONSTRAINT fk_bankaccountstatements_bankaccount_id FOREIGN KEY ("bankAccount") REFERENCES bankaccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  talerwithdrawals ADD CONSTRAINT fk_talerwithdrawals_walletbankaccount_id FOREIGN KEY ("walletBankAccount") REFERENCES bankaccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

COMMIT;
