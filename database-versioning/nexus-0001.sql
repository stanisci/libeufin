-- NOTE: SOON TO BE OBSOLETED VERSION.
-- NOTE REFER TO THE new/ SUBFOLDER TO REFACTOR NEXUS SQL FILES.

-- Compatible with LibEuFin version: 1fe2687aaf696c8566367fe7ed082f1d78e6b78d

BEGIN;

SELECT _v.register_patch('nexus-0001', NULL, NULL);

CREATE TABLE IF NOT EXISTS nexususers 
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,"password" TEXT NOT NULL
  ,superuser BOOLEAN NOT NULL
  );

CREATE TABLE IF NOT EXISTS nexusbankconnections 
  (id BIGSERIAL PRIMARY KEY
  ,"connectionId" TEXT NOT NULL
  ,"type" TEXT NOT NULL
  ,dialect TEXT
  ,"user" BIGINT NOT NULL 
  ,CONSTRAINT fk_nexusbankconnections_user_id FOREIGN KEY ("user") REFERENCES nexususers(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS xlibeufinbankusers 
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,"password" TEXT NOT NULL
  ,"baseUrl" TEXT NOT NULL
  ,"nexusBankConnection" BIGINT NOT NULL
  ,CONSTRAINT fk_xlibeufinbankusers_nexusbankconnection_id FOREIGN KEY ("nexusBankConnection") REFERENCES nexusbankconnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS nexusscheduledtasks 
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

CREATE TABLE IF NOT EXISTS nexusbankaccounts 
  (id BIGSERIAL PRIMARY KEY
  ,"bankAccountId" TEXT NOT NULL
  ,"accountHolder" TEXT NOT NULL
  ,iban TEXT NOT NULL
  ,"bankCode" TEXT NOT NULL
  ,"defaultBankConnection" BIGINT NULL
  ,"lastStatementCreationTimestamp" BIGINT NULL
  ,"lastReportCreationTimestamp" BIGINT NULL
  ,"lastNotificationCreationTimestamp" BIGINT NULL
  ,"highestSeenBankMessageSerialId" BIGINT NOT NULL
  ,pain001counter BIGINT DEFAULT 1 NOT NULL
  ,CONSTRAINT fk_nexusbankaccounts_defaultbankconnection_id FOREIGN KEY ("defaultBankConnection") REFERENCES nexusbankconnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

ALTER TABLE
  nexusbankaccounts ADD CONSTRAINT nexusbankaccounts_bankaccountid_unique UNIQUE ("bankAccountId");

CREATE TABLE IF NOT EXISTS nexusbanktransactions 
  (id BIGSERIAL PRIMARY KEY
  ,"accountTransactionId" TEXT NOT NULL
  ,"bankAccount" BIGINT NOT NULL
  ,"creditDebitIndicator" TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,amount TEXT NOT NULL
  ,status VARCHAR(16) NOT NULL
  ,"updatedBy" BIGINT NULL
  ,"transactionJson" TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS paymentinitiations 
  (id BIGSERIAL PRIMARY KEY
  ,"bankAccount" BIGINT NOT NULL
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
  ,"rawConfirmation" BIGINT NULL
  );

CREATE TABLE IF NOT EXISTS nexusebicssubscribers 
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
  ,CONSTRAINT fk_nexusebicssubscribers_nexusbankconnection_id FOREIGN KEY ("nexusBankConnection") REFERENCES nexusbankconnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS nexusbankbalances 
  (id BIGSERIAL PRIMARY KEY
  ,balance TEXT NOT NULL
  ,"creditDebitIndicator" TEXT NOT NULL
  ,"bankAccount" BIGINT NOT NULL
  ,"date" TEXT NOT NULL
  ,CONSTRAINT fk_nexusbankbalances_bankaccount_id FOREIGN KEY ("bankAccount") REFERENCES nexusbankaccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS anastasisincomingpayments 
  (id BIGSERIAL PRIMARY KEY
  ,payment BIGINT NOT NULL
  ,subject TEXT NOT NULL
  ,"timestampMs" BIGINT NOT NULL
  ,"incomingPaytoUri" TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS talerincomingpayments 
  (id BIGSERIAL PRIMARY KEY
  ,payment BIGINT NOT NULL
  ,"reservePublicKey" TEXT NOT NULL
  ,"timestampMs" BIGINT NOT NULL
  ,"incomingPaytoUri" TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS facades 
  (id BIGSERIAL PRIMARY KEY
  ,"facadeName" TEXT NOT NULL
  ,"type" TEXT NOT NULL
  ,creator BIGINT NOT NULL
  ,CONSTRAINT fk_facades_creator_id FOREIGN KEY (creator) REFERENCES nexususers(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

ALTER TABLE
  facades ADD CONSTRAINT facades_facadename_unique UNIQUE ("facadeName");

CREATE TABLE IF NOT EXISTS talerrequestedpayments 
  (id BIGSERIAL PRIMARY KEY
  ,facade BIGINT NOT NULL
  ,payment BIGINT NOT NULL
  ,"requestUid" TEXT NOT NULL
  ,amount TEXT NOT NULL
  ,"exchangeBaseUrl" TEXT NOT NULL
  ,wtid TEXT NOT NULL
  ,"creditAccount" TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS facadestate 
  (id BIGSERIAL PRIMARY KEY
  ,"bankAccount" TEXT NOT NULL
  ,"bankConnection" TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,"reserveTransferLevel" TEXT NOT NULL
  ,facade BIGINT NOT NULL
  ,"highestSeenMessageSerialId" BIGINT DEFAULT 0 NOT NULL
  ,CONSTRAINT fk_facadestate_facade_id FOREIGN KEY (facade) REFERENCES facades(id) ON DELETE CASCADE ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS talerinvalidincomingpayments 
  (id BIGSERIAL PRIMARY KEY
  ,payment BIGINT NOT NULL
  ,"timestampMs" BIGINT NOT NULL
  ,refunded BOOLEAN DEFAULT false NOT NULL
  );

CREATE TABLE IF NOT EXISTS nexusbankmessages 
  (id BIGSERIAL PRIMARY KEY
  ,"bankConnection" BIGINT NOT NULL
  ,message bytea NOT NULL
  ,"messageId" TEXT NULL
  ,"fetchLevel" VARCHAR(16) NOT NULL
  ,errors BOOLEAN DEFAULT false NOT NULL
  ,CONSTRAINT fk_nexusbankmessages_bankconnection_id FOREIGN KEY ("bankConnection") REFERENCES nexusbankconnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS offeredbankaccounts 
  (id BIGSERIAL PRIMARY KEY
  ,"offeredAccountId" TEXT NOT NULL
  ,"bankConnection" BIGINT NOT NULL
  ,iban TEXT NOT NULL
  ,"bankCode" TEXT NOT NULL
  ,"holderName" TEXT NOT NULL
  ,imported BIGINT NULL
  ,CONSTRAINT fk_offeredbankaccounts_bankconnection_id FOREIGN KEY ("bankConnection") REFERENCES nexusbankconnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,CONSTRAINT fk_offeredbankaccounts_imported_id FOREIGN KEY (imported) REFERENCES nexusbankaccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

ALTER TABLE
  offeredbankaccounts ADD CONSTRAINT offeredbankaccounts_offeredaccountid_bankconnection_unique UNIQUE ("offeredAccountId", "bankConnection");

CREATE TABLE IF NOT EXISTS nexuspermissions 
  (id BIGSERIAL PRIMARY KEY
  ,"resourceType" TEXT NOT NULL
  ,"resourceId" TEXT NOT NULL
  ,"subjectType" TEXT NOT NULL
  ,"subjectName" TEXT NOT NULL
  ,"permissionName" TEXT NOT NULL
  );

ALTER TABLE
  nexuspermissions ADD CONSTRAINT nexuspermissions_resourcetype_resourceid_subjecttype_subjectnam UNIQUE ("resourceType", "resourceId", "subjectType", "subjectName", "permissionName");

ALTER TABLE
  nexusbanktransactions ADD CONSTRAINT fk_nexusbanktransactions_bankaccount_id FOREIGN KEY ("bankAccount") REFERENCES nexusbankaccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  nexusbanktransactions ADD CONSTRAINT fk_nexusbanktransactions_updatedby_id FOREIGN KEY ("updatedBy") REFERENCES nexusbanktransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  paymentinitiations ADD CONSTRAINT fk_paymentinitiations_bankaccount_id FOREIGN KEY ("bankAccount") REFERENCES nexusbankaccounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  paymentinitiations ADD CONSTRAINT fk_paymentinitiations_rawconfirmation_id FOREIGN KEY ("rawConfirmation") REFERENCES nexusbanktransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  anastasisincomingpayments ADD CONSTRAINT fk_anastasisincomingpayments_payment_id FOREIGN KEY (payment) REFERENCES nexusbanktransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  talerincomingpayments ADD CONSTRAINT fk_talerincomingpayments_payment_id FOREIGN KEY (payment) REFERENCES nexusbanktransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  talerrequestedpayments ADD CONSTRAINT fk_talerrequestedpayments_facade_id FOREIGN KEY (facade) REFERENCES facades(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  talerrequestedpayments ADD CONSTRAINT fk_talerrequestedpayments_payment_id FOREIGN KEY (payment) REFERENCES paymentinitiations(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE
  talerinvalidincomingpayments ADD CONSTRAINT fk_talerinvalidincomingpayments_payment_id FOREIGN KEY (payment) REFERENCES nexusbanktransactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

COMMIT
