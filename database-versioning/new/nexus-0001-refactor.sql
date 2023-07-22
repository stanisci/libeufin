-- To Do: comments, although '--' vs 'COMMENT ON' is under discussion.

BEGIN;

SELECT _v.register_patch('nexus-0001', NULL, NULL);

-- start of: user management

CREATE TABLE IF NOT EXISTS NexusUsers
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,"password" TEXT NOT NULL
  ,superuser BOOLEAN NOT NULL
  );

-- end of: user management

-- start of: connection management

CREATE TABLE IF NOT EXISTS NexusBankConnections 
  (id BIGSERIAL PRIMARY KEY
  ,"connectionId" TEXT NOT NULL
  ,"type" TEXT NOT NULL
  ,dialect TEXT NULL
  ,"user" BIGINT NOT NULL
  ,CONSTRAINT fk_nexusbankconnections_user_id FOREIGN KEY ("user") REFERENCES NexusUsers(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS NexusEbicsSubscribers
  (id BIGSERIAL PRIMARY KEY
  ,"ebicsURL" TEXT NOT NULL
  ,"hostID" TEXT NOT NULL
  ,"partnerID" TEXT NOT NULL
  ,"userID" TEXT NOT NULL
  ,"systemID" TEXT NULL
  ,"signaturePrivateKey" bytea NOT NULL
  ,"encryptionPrivateKey" bytea NOT NULL
  ,"authenticationPrivateKey" bytea NOT NULL
  ,"bankEncryptionPublicKey" bytea NULL
  ,"bankAuthenticationPublicKey" bytea NULL
  ,"nexusBankConnection" BIGINT NOT NULL
  ,"ebicsIniState" VARCHAR(16) NOT NULL
  ,"ebicsHiaState" VARCHAR(16) NOT NULL
  ,CONSTRAINT fk_nexusebicssubscribers_nexusbankconnection_id FOREIGN KEY ("nexusBankConnection") REFERENCES NexusBankConnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS XLibeufinBankUsers
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,"password" TEXT NOT NULL
  ,"baseUrl" TEXT NOT NULL
  ,"nexusBankConnection" BIGINT NOT NULL
  ,CONSTRAINT fk_xlibeufinbankusers_nexusbankconnection_id FOREIGN KEY ("nexusBankConnection") REFERENCES NexusBankConnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS OfferedBankAccounts 
  (id BIGSERIAL PRIMARY KEY
  ,"offeredAccountId" TEXT NOT NULL
  ,"bankConnection" BIGINT NOT NULL
  ,iban TEXT NOT NULL
  ,"bankCode" TEXT NOT NULL
  ,"holderName" TEXT NOT NULL
  ,imported BIGINT NULL
  ,CONSTRAINT fk_offeredbankaccounts_bankconnection_id FOREIGN KEY ("bankConnection") REFERENCES nexusbankconnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,CONSTRAINT fk_offeredbankaccounts_imported_id FOREIGN KEY (imported) REFERENCES NexusBankAccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

-- end of: connection management

-- start of: background tasks

CREATE TABLE IF NOT EXISTS NexusScheduledTasks 
  (id BIGSERIAL PRIMARY KEY
  ,"resourceType" TEXT NOT NULL
  ,"resourceId" TEXT NOT NULL
  ,"taskName" TEXT NOT NULL
  ,"taskType" TEXT NOT NULL
  ,"taskCronspec" TEXT NOT NULL
  ,"taskParams" TEXT NOT NULL
  ,"nextScheduledExecutionSec" BIGINT NULL
  ,"lastScheduledExecutionSec" BIGINT NULL
  );

-- end of: background tasks

-- start of: facades management

CREATE TABLE IF NOT EXISTS FacadeState 
  (id BIGSERIAL PRIMARY KEY
  ,"bankAccount" TEXT NOT NULL
  ,"bankConnection" TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,"reserveTransferLevel" TEXT NOT NULL
  ,facade BIGINT NOT NULL
  ,"highestSeenMessageSerialId" BIGINT DEFAULT 0 NOT NULL
  ,CONSTRAINT fk_facadestate_facade_id FOREIGN KEY (facade) REFERENCES facades(id) ON DELETE CASCADE ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS facades 
  (id BIGSERIAL PRIMARY KEY
  ,"facadeName" TEXT NOT NULL UNIQUE
  ,"type" TEXT NOT NULL
  ,creator BIGINT NOT NULL
  ,CONSTRAINT fk_facades_creator_id FOREIGN KEY (creator) REFERENCES NexusUsers(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS NexusPermissions 
  (id BIGSERIAL PRIMARY KEY
  ,"resourceType" TEXT NOT NULL
  ,"resourceId" TEXT NOT NULL
  ,"subjectType" TEXT NOT NULL
  ,"subjectName" TEXT NOT NULL
  ,"permissionName" TEXT NOT NULL
  );

-- end of: general facades management

-- start of: Taler facade management

CREATE TABLE IF NOT EXISTS TalerIncomingPayments 
  (id BIGSERIAL PRIMARY KEY
  ,payment NOT NULL REFERENCES NexusBankTransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"reservePublicKey" TEXT NOT NULL
  ,"timestampMs" BIGINT NOT NULL
  ,"incomingPaytoUri" TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS TalerRequestedPayments 
  (id BIGSERIAL PRIMARY KEY
  ,facade NOT NULL REFERENCES facades(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,payment NOT NULL REFERENCES PaymentInitiations(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"requestUid" TEXT NOT NULL
  ,amount TEXT NOT NULL
  ,"exchangeBaseUrl" TEXT NOT NULL
  ,wtid TEXT NOT NULL
  ,"creditAccount" TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS TalerInvalidIncomingPayments 
  (id BIGSERIAL PRIMARY KEY
  ,payment NOT NULL REFERENCES NexusBankTransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"timestampMs" BIGINT NOT NULL
  ,refunded BOOLEAN DEFAULT false NOT NULL
  );

-- end of: Taler facade management

-- start of: Anastasis facade management

CREATE TABLE IF NOT EXISTS AnastasisIncomingPayments 
  (id BIGSERIAL PRIMARY KEY
  ,payment NOT NULL REFERENCES NexusBankTransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,subject TEXT NOT NULL
  ,"timestampMs" BIGINT NOT NULL
  ,"incomingPaytoUri" TEXT NOT NULL
  );

-- end of: Anastasis facade management

-- start of: core banking

CREATE TABLE IF NOT EXISTS NexusBankAccounts
  (id BIGSERIAL PRIMARY KEY
  ,"bankAccountId" TEXT NOT NULL UNIQUE
  ,"accountHolder" TEXT NOT NULL
  ,iban TEXT NOT NULL
  ,"bankCode" TEXT NOT NULL
  ,"defaultBankConnection" BIGINT NULL
  ,"lastStatementCreationTimestamp" BIGINT NULL
  ,"lastReportCreationTimestamp" BIGINT NULL
  ,"lastNotificationCreationTimestamp" BIGINT NULL
  ,"highestSeenBankMessageSerialId" BIGINT NOT NULL
  ,pain001counter BIGINT DEFAULT 1 NOT NULL
  ,CONSTRAINT fk_nexusbankaccounts_defaultbankconnection_id FOREIGN KEY ("defaultBankConnection") REFERENCES NexusBankConnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS NexusBankTransactions 
  (id BIGSERIAL PRIMARY KEY
  ,"accountTransactionId" TEXT NOT NULL
  ,"bankAccount" NOT NULL REFERENCES NexusBankAccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"creditDebitIndicator" TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,amount TEXT NOT NULL
  ,status VARCHAR(16) NOT NULL
  ,"updatedBy" BIGINT NULL REFERENCES NexusBankTransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"transactionJson" TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS PaymentInitiations
  (id BIGSERIAL PRIMARY KEY
  ,"bankAccount" NOT NULL REFERENCES NexusBankAccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,"preparationDate" BIGINT NOT NULL
  ,"submissionDate" BIGINT NULL
  ,"sum" TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,"endToEndId" TEXT NOT NULL
  ,"paymentInformationId" TEXT NOT NULL
  ,"instructionId" TEXT NOT NULL
  ,subject TEXT NOT NULL
  ,"creditorIban" TEXT NOT NULL
  ,"creditorBic" TEXT NULL
  ,"creditorName" TEXT NOT NULL
  ,submitted BOOLEAN DEFAULT false NOT NULL
  ,invalid BOOLEAN NULL
  ,"messageId" TEXT NOT NULL
  ,"rawConfirmation" BIGINT NULL REFERENCES NexusBankTransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS NexusBankBalances -- table never used
  (id BIGSERIAL PRIMARY KEY
  ,balance TEXT NOT NULL
  ,"creditDebitIndicator" TEXT NOT NULL
  ,"bankAccount" BIGINT NOT NULL
  ,"date" TEXT NOT NULL
  ,CONSTRAINT fk_nexusbankbalances_bankaccount_id FOREIGN KEY ("bankAccount") REFERENCES NexusBankAccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS NexusBankMessages
  (id BIGSERIAL PRIMARY KEY
  ,"bankConnection" BIGINT NOT NULL
  ,message bytea NOT NULL
  ,"messageId" TEXT NULL
  ,"fetchLevel" VARCHAR(16) NOT NULL
  ,errors BOOLEAN DEFAULT false NOT NULL
  ,CONSTRAINT fk_nexusbankmessages_bankconnection_id FOREIGN KEY ("bankConnection") REFERENCES NexusBankConnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

ALTER TABLE
  OfferedBankAccounts ADD CONSTRAINT offeredbankaccounts_offeredaccountid_bankconnection_unique UNIQUE ("offeredAccountId", "bankConnection");

ALTER TABLE
  NexusPermissions ADD CONSTRAINT nexuspermissions_resourcetype_resourceid_subjecttype_subjectnam UNIQUE ("resourceType", "resourceId", "subjectType", "subjectName", "permissionName");

-- end of: core banking

COMMIT
