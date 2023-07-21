-- Under discussion:

-- amount format
-- timestamp format
-- comment format: '--' vs 'COMMENT ON'

BEGIN;

SELECT _v.register_patch('sandbox-0001', NULL, NULL);

-- start of: demobank config tables

CREATE TABLE IF NOT EXISTS DemobankConfigs 
  (id BIGSERIAL PRIMARY KEY
  ,name TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS DemobankConfigPairs 
  (id BIGSERIAL PRIMARY KEY
  ,"demobankName" TEXT NOT NULL
  ,"configKey" TEXT NOT NULL
  ,"configValue" TEXT NULL
  );

-- end of: demobank config tables

-- start of: bank accounts

CREATE TABLE IF NOT EXISTS DemobankCustomers 
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,"passwordHash" TEXT NOT NULL
  ,"name" TEXT NULL
  ,email TEXT NULL
  ,phone TEXT NULL
  ,cashout_address TEXT NULL
  );

CREATE TABLE IF NOT EXISTS BankAccounts 
  (id SERIAL PRIMARY KEY
  ,iban TEXT NOT NULL
  ,bic TEXT NOT NULL -- NOTE: This had a default of 'SANDBOXX', now Kotlin must keep it.
  ,"label" TEXT NOT NULL UNIQUE
  ,"owner" TEXT NOT NULL
  ,"isPublic" BOOLEAN DEFAULT false NOT NULL
  ,"demoBank" REFERENCES DemobankConfigs(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"lastTransaction" NULL REFERENCES BankAccountTransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT -- FIXME: under discussion on MM, might be removed.
  ,"lastFiatSubmission" NULL REFERENCES BankAccountTransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"lastFiatFetch" TEXT DEFAULT '0' NOT NULL
  ,"balance" TEXT DEFAULT '0'
  );

-- end of: bank accounts

-- start of: money transactions

CREATE TABLE IF NOT EXISTS BankAccountTransactions 
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
  ,account INT NOT NULL REFERENCES BankAccounts(id) ON DELETE CASCADE ON UPDATE RESTRICT
  ,demobank NOT NULL REFERENCES DemobankConfigs(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

-- end of: money transactions

-- start of: cashout management

CREATE TABLE IF NOT EXISTS CashoutSubmissions 
  (id BIGSERIAL PRIMARY KEY
  ,"localTransaction" NOT NULL UNIQUE REFERENCES BankAccountTransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"maybeNexusResponse" TEXT NULL
  ,"submissionTime" BIGINT NULL
  );

CREATE TABLE IF NOT EXISTS CashoutOperations 
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

-- end of: cashout management

-- start of: EBICS management

CREATE TABLE IF NOT EXISTS EbicsHosts 
  (id SERIAL PRIMARY KEY
  ,"hostID" TEXT NOT NULL
  ,"ebicsVersion" TEXT NOT NULL
  ,"signaturePrivateKey" bytea NOT NULL
  ,"encryptionPrivateKey" bytea NOT NULL
  ,"authenticationPrivateKey" bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS EbicsSubscribers 
  (id SERIAL PRIMARY KEY
  ,"userID" TEXT NOT NULL
  ,"partnerID" TEXT NOT NULL
  ,"systemID" TEXT NULL
  ,"hostID" TEXT NOT NULL
  ,"signatureKey" INT NULL REFERENCES EbicsSubscriberPublicKeys(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"encryptionKey" INT NULL REFERENCES EbicsSubscriberPublicKeys(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"authorizationKey" INT NULL REFERENCES EbicsSubscriberPublicKeys(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"nextOrderID" INT NOT NULL
  ,"state" INT NOT NULL
  ,"bankAccount" INT NULL REFERENCES BankAccounts(id) ON DELETE CASCADE ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS EbicsSubscriberPublicKeys
  (id SERIAL PRIMARY KEY
   ,"rsaPublicKey" bytea NOT NULL
   ,"state" INT NOT NULL
  );

CREATE TABLE IF NOT EXISTS EbicsDownloadTransactions 
  ("transactionID" TEXT NOT NULL
  ,"orderType" TEXT NOT NULL
  ,host INT NOT NULL REFERENCES EbicsHosts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,subscriber INT NOT NULL REFERENCES EbicsSubscribers(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"encodedResponse" TEXT NOT NULL
  ,"transactionKeyEnc" bytea NOT NULL
  ,"numSegments" INT NOT NULL
  ,"segmentSize" INT NOT NULL
  ,"receiptReceived" BOOLEAN NOT NULL
  );

CREATE TABLE IF NOT EXISTS EbicsUploadTransactions 
  ("transactionID" TEXT NOT NULL
  ,"orderType" TEXT NOT NULL
  ,"orderID" TEXT NOT NULL
  ,host INT NOT NULL REFERENCES EbicsHosts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,subscriber INT NOT NULL REFERENCES EbicsSubscribers(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"numSegments" INT NOT NULL
  ,"lastSeenSegment" INT NOT NULL
  ,"transactionKeyEnc" bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS EbicsUploadTransactionChunks 
  ("transactionID" TEXT NOT NULL
  ,"chunkIndex" INT NOT NULL
  ,"chunkContent" bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS EbicsOrderSignatures 
  (id SERIAL PRIMARY KEY
  ,"orderID" TEXT NOT NULL
  ,"orderType" TEXT NOT NULL
  ,"partnerID" TEXT NOT NULL
  ,"userID" TEXT NOT NULL
  ,"signatureAlgorithm" TEXT NOT NULL
  ,"signatureValue" bytea NOT NULL
  );

-- end of: EBICS management

-- start of: accounts activity report 

CREATE TABLE IF NOT EXISTS BankAccountFreshTransactions 
  (id BIGSERIAL PRIMARY KEY
  ,"transaction" NOT NULL REFERENCES BankAccountTransactions(id) ON DELETE CASCADE ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS BankAccountReports 
  (id SERIAL PRIMARY KEY
  ,"reportId" TEXT NOT NULL
  ,"creationTime" BIGINT NOT NULL
  ,"xmlMessage" TEXT NOT NULL
  ,"bankAccount" INT NOT NULL REFERENCES BankAccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS BankAccountStatements 
  (id SERIAL PRIMARY KEY
  ,"statementId" TEXT NOT NULL
  ,"creationTime" BIGINT NOT NULL
  ,"xmlMessage" TEXT NOT NULL
  ,"bankAccount" INT NOT NULL REFERENCES BankAccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"balanceClbd" TEXT NOT NULL
  );

-- end of: accounts activity report 

-- start of: Taler integration

CREATE TABLE IF NOT EXISTS TalerWithdrawals 
  (id BIGSERIAL PRIMARY KEY
  ,wopid uuid NOT NULL
  ,amount TEXT NOT NULL
  ,"selectionDone" BOOLEAN DEFAULT false NOT NULL
  ,aborted BOOLEAN DEFAULT false NOT NULL
  ,"confirmationDone" BOOLEAN DEFAULT false NOT NULL
  ,"reservePub" TEXT NULL
  ,"selectedExchangePayto" TEXT NULL
  ,"walletBankAccount" INT NOT NULL REFERENCES BankAccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

-- end of: Taler integration

COMMIT;
