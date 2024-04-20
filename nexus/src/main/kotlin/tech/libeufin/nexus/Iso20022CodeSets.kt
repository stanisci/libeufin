/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

// THIS FILE IS GENERATED, DO NOT EDIT

package tech.libeufin.nexus

enum class ExternalStatusReasonCode(val isoCode: String, val description: String) {
	AB01("AbortedClearingTimeout", "Clearing process aborted due to timeout."),
	AB02("AbortedClearingFatalError", "Clearing process aborted due to a fatal error."),
	AB03("AbortedSettlementTimeout", "Settlement aborted due to timeout."),
	AB04("AbortedSettlementFatalError", "Settlement process aborted due to a fatal error."),
	AB05("TimeoutCreditorAgent", "Transaction stopped due to timeout at the Creditor Agent."),
	AB06("TimeoutInstructedAgent", "Transaction stopped due to timeout at the Instructed Agent."),
	AB07("OfflineAgent", "Agent of message is not online."),
	AB08("OfflineCreditorAgent", "Creditor Agent is not online."),
	AB09("ErrorCreditorAgent", "Transaction stopped due to error at the Creditor Agent."),
	AB10("ErrorInstructedAgent", "Transaction stopped due to error at the Instructed Agent."),
	AB11("TimeoutDebtorAgent", "Transaction stopped due to timeout at the Debtor Agent."),
	AC01("IncorrectAccountNumber", "Account number is invalid or missing."),
	AC02("InvalidDebtorAccountNumber", "Debtor account number invalid or missing"),
	AC03("InvalidCreditorAccountNumber", "Creditor account number invalid or missing"),
	AC04("ClosedAccountNumber", "Account number specified has been closed on the bank of account's books."),
	AC05("ClosedDebtorAccountNumber", "Debtor account number closed"),
	AC06("BlockedAccount", "Account specified is blocked, prohibiting posting of transactions against it."),
	AC07("ClosedCreditorAccountNumber", "Creditor account number closed"),
	AC08("InvalidBranchCode", "Branch code is invalid or missing"),
	AC09("InvalidAccountCurrency", "Account currency is invalid or missing"),
	AC10("InvalidDebtorAccountCurrency", "Debtor account currency is invalid or missing"),
	AC11("InvalidCreditorAccountCurrency", "Creditor account currency is invalid or missing"),
	AC12("InvalidAccountType", "Account type missing or invalid."),
	AC13("InvalidDebtorAccountType", "Debtor account type missing or invalid"),
	AC14("InvalidCreditorAccountType", "Creditor account type missing or invalid"),
	AC15("AccountDetailsChanged", "The account details for the counterparty have changed."),
	AC16("CardNumberInvalid", "Credit or debit card number is invalid."),
	AEXR("AlreadyExpiredRTP", "Request-to-pay Expiry Date and Time has already passed."),
	AG01("TransactionForbidden", "Transaction forbidden on this type of account (formerly NoAgreement)"),
	AG02("InvalidBankOperationCode", "Bank Operation code specified in the message is not valid for receiver"),
	AG03("TransactionNotSupported", "Transaction type not supported/authorized on this account"),
	AG04("InvalidAgentCountry", "Agent country code is missing or invalid."),
	AG05("InvalidDebtorAgentCountry", "Debtor agent country code is missing or invalid"),
	AG06("InvalidCreditorAgentCountry", "Creditor agent country code is missing or invalid"),
	AG07("UnsuccesfulDirectDebit", "Debtor account cannot be debited for a generic reason."),
	AG08("InvalidAccessRights", "Transaction failed due to invalid or missing user or access right"),
	AG09("PaymentNotReceived", "Original payment never received."),
	AG10("AgentSuspended", "Agent of message is suspended from the Real Time Payment system."),
	AG11("CreditorAgentSuspended", "Creditor Agent of message is suspended from the Real Time Payment system."),
	AG12("NotAllowedBookTransfer", "Payment orders made by transferring funds from one account to another at the same financial institution (bank or payment institution) are not allowed."),
	AG13("ForbiddenReturnPayment", "Returned payments derived from previously returned transactions are not allowed."),
	AGNT("IncorrectAgent", "Agent in the payment workflow is incorrect"),
	ALAC("AlreadyAcceptedRTP", "Request-to-pay has already been accepted by the Debtor."),
	AM01("ZeroAmount", "Specified message amount is equal to zero"),
	AM02("NotAllowedAmount", "Specific transaction/message amount is greater than allowed maximum"),
	AM03("NotAllowedCurrency", "Specified message amount is an non processable currency outside of existing agreement"),
	AM04("InsufficientFunds", "Amount of funds available to cover specified message amount is insufficient."),
	AM05("Duplication", "Duplication"),
	AM06("TooLowAmount", "Specified transaction amount is less than agreed minimum."),
	AM07("BlockedAmount", "Amount specified in message has been blocked by regulatory authorities."),
	AM09("WrongAmount", "Amount received is not the amount agreed or expected"),
	AM10("InvalidControlSum", "Sum of instructed amounts does not equal the control sum."),
	AM11("InvalidTransactionCurrency", "Transaction currency is invalid or missing"),
	AM12("InvalidAmount", "Amount is invalid or missing"),
	AM13("AmountExceedsClearingSystemLimit", "Transaction amount exceeds limits set by clearing system"),
	AM14("AmountExceedsAgreedLimit", "Transaction amount exceeds limits agreed between bank and client"),
	AM15("AmountBelowClearingSystemMinimum", "Transaction amount below minimum set by clearing system"),
	AM16("InvalidGroupControlSum", "Control Sum at the Group level is invalid"),
	AM17("InvalidPaymentInfoControlSum", "Control Sum at the Payment Information level is invalid"),
	AM18("InvalidNumberOfTransactions", "Number of transactions is invalid or missing."),
	AM19("InvalidGroupNumberOfTransactions", "Number of transactions at the Group level is invalid or missing"),
	AM20("InvalidPaymentInfoNumberOfTransactions", "Number of transactions at the Payment Information level is invalid"),
	AM21("LimitExceeded", "Transaction amount exceeds limits agreed between bank and client."),
	AM22("ZeroAmountNotApplied", "Unable to apply zero amount to designated account. For example, where the rules of a service allow the use of zero amount payments, however the back-office system is unable to apply the funds to the account. If the rules of a service prohibit the use of zero amount payments, then code AM01 is used to report the error condition."),
	AM23("AmountExceedsSettlementLimit", "Transaction amount exceeds settlement limit."),
	APAR("AlreadyPaidRTP", "Request To Pay has already been paid by the Debtor."),
	ARFR("AlreadyRefusedRTP", "Request-to-pay has already been refused by the Debtor."),
	ARJR("AlreadyRejectedRTP", "Request-to-pay has already been rejected."),
	ATNS("AttachementsNotSupported", "Attachments to the request-to-pay are not supported."),
	BE01("InconsistenWithEndCustomer", "Identification of end customer is not consistent with associated account number. (formerly CreditorConsistency)."),
	BE04("MissingCreditorAddress", "Specification of creditor's address, which is required for payment, is missing/not correct (formerly IncorrectCreditorAddress)."),
	BE05("UnrecognisedInitiatingParty", "Party who initiated the message is not recognised by the end customer"),
	BE06("UnknownEndCustomer", "End customer specified is not known at associated Sort/National Bank Code or does no longer exist in the books"),
	BE07("MissingDebtorAddress", "Specification of debtor's address, which is required for payment, is missing/not correct."),
	BE08("MissingDebtorName", "Debtor name is missing"),
	BE09("InvalidCountry", "Country code is missing or Invalid."),
	BE10("InvalidDebtorCountry", "Debtor country code is missing or invalid"),
	BE11("InvalidCreditorCountry", "Creditor country code is missing or invalid"),
	BE12("InvalidCountryOfResidence", "Country code of residence is missing or Invalid."),
	BE13("InvalidDebtorCountryOfResidence", "Country code of debtor's residence is missing or Invalid"),
	BE14("InvalidCreditorCountryOfResidence", "Country code of creditor's residence is missing or Invalid"),
	BE15("InvalidIdentificationCode", "Identification code missing or invalid."),
	BE16("InvalidDebtorIdentificationCode", "Debtor or Ultimate Debtor identification code missing or invalid"),
	BE17("InvalidCreditorIdentificationCode", "Creditor or Ultimate Creditor identification code missing or invalid"),
	BE18("InvalidContactDetails", "Contact details missing or invalid"),
	BE19("InvalidChargeBearerCode", "Charge bearer code for transaction type is invalid"),
	BE20("InvalidNameLength", "Name length exceeds local rules for payment type."),
	BE21("MissingName", "Name missing or invalid.  Generic usage if cannot specifically identify debtor or creditor."),
	BE22("MissingCreditorName", "Creditor name is missing"),
	BE23("AccountProxyInvalid", "Phone number or email address, or any other proxy, used as the account proxy is unknown or invalid."),
	CERI("CheckERI", "Credit transfer is not tagged as an Extended Remittance Information (ERI) transaction but contains ERI."),
	CH03("RequestedExecutionDateOrRequestedCollectionDateTooFarInFuture", "Value in Requested Execution Date or Requested Collection Date is too far in the future"),
	CH04("RequestedExecutionDateOrRequestedCollectionDateTooFarInPast", "Value in Requested Execution Date or Requested Collection Date is too far in the past"),
	CH07("ElementIsNotToBeUsedAtB-andC-Level", "Element is not to be used at B- and C-Level"),
	CH09("MandateChangesNotAllowed", "Mandate changes are not allowed"),
	CH10("InformationOnMandateChangesMissing", "Information on mandate changes are missing"),
	CH11("CreditorIdentifierIncorrect", "Value in Creditor Identifier is incorrect"),
	CH12("CreditorIdentifierNotUnambiguouslyAtTransaction-Level", "Creditor Identifier is ambiguous at Transaction Level"),
	CH13("OriginalDebtorAccountIsNotToBeUsed", "Original Debtor Account is not to be used"),
	CH14("OriginalDebtorAgentIsNotToBeUsed", "Original Debtor Agent  is not to be used"),
	CH15("ElementContentIncludesMoreThan140Characters", "Content Remittance Information/Structured includes more than 140 characters"),
	CH16("ElementContentFormallyIncorrect", "Content is incorrect"),
	CH17("ElementNotAdmitted", "Element is not allowed"),
	CH19("ValuesWillBeSetToNextTARGETday", "Values in Interbank Settlement Date or Requested Collection Date will be set to the next TARGET day"),
	CH20("DecimalPointsNotCompatibleWithCurrency", "Number of decimal points not compatible with the currency"),
	CH21("RequiredCompulsoryElementMissing", "Mandatory element is missing"),
	CH22("COREandB2BwithinOnemessage", "SDD CORE and B2B not permitted within one message"),
	CHQC("ChequeSettledOnCreditorAccount", "Cheque has been presented in cheque clearing and settled on the creditor’s account."),
	CN01("AuthorisationCancelled", "Authorisation is cancelled."),
	CNOR("CreditorBankIsNotRegistered", "Creditor bank is not registered under this BIC in the CSM"),
	CURR("IncorrectCurrency", "Currency of the payment is incorrect"),
	CUST("RequestedByCustomer", "Cancellation requested by the Debtor"),
	DC02("SettlementNotReceived", "Rejection of a payment due to covering FI settlement not being received."),
	DNOR("DebtorBankIsNotRegistered", "Debtor bank is not registered under this BIC in the CSM"),
	DS01("ElectronicSignaturesCorrect", "The electronic signature(s) is/are correct"),
	DS02("OrderCancelled", "An authorized user has cancelled the order"),
	DS03("OrderNotCancelled", "The user’s attempt to cancel the order was not successful"),
	DS04("OrderRejected", "The order was rejected by the bank side (for reasons concerning content)"),
	DS05("OrderForwardedForPostprocessing", "The order was correct and could be forwarded for postprocessing"),
	DS06("TransferOrder", "The order was transferred to VEU"),
	DS07("ProcessingOK", "All actions concerning the order could be done by the EBICS bank server"),
	DS08("DecompressionError", "The decompression of the file was not successful"),
	DS09("DecryptionError", "The decryption of the file was not successful"),
	DS0A("DataSignRequested", "Data signature is required."),
	DS0B("UnknownDataSignFormat", "Data signature for the format is not available or invalid."),
	DS0C("SignerCertificateRevoked", "The signer certificate is revoked."),
	DS0D("SignerCertificateNotValid", "The signer certificate is not valid (revoked or not active)."),
	DS0E("IncorrectSignerCertificate", "The signer certificate is not present."),
	DS0F("SignerCertificationAuthoritySignerNotValid", "The authority of the signer certification sending the certificate is unknown."),
	DS0G("NotAllowedPayment", "Signer is not allowed to sign this operation type."),
	DS0H("NotAllowedAccount", "Signer is not allowed to sign for this account."),
	DS0K("NotAllowedNumberOfTransaction", "The number of transaction is over the number allowed for this signer."),
	DS10("Signer1CertificateRevoked", "The certificate is revoked for the first signer."),
	DS11("Signer1CertificateNotValid", "The certificate is not valid (revoked or not active) for the first signer."),
	DS12("IncorrectSigner1Certificate", "The certificate is not present for the first signer."),
	DS13("SignerCertificationAuthoritySigner1NotValid", "The authority of signer certification sending the certificate is unknown for the first signer."),
	DS14("UserDoesNotExist", "The user is unknown on the server"),
	DS15("IdenticalSignatureFound", "The same signature has already been sent to the bank"),
	DS16("PublicKeyVersionIncorrect", "The public key version is not correct. This code is returned when a customer sends signature files to the financial institution after conversion from an older program version (old ES format) to a new program version (new ES format) without having carried out re-initialisation with regard to a public key change."),
	DS17("DifferentOrderDataInSignatures", "Order data and signatures don’t match"),
	DS18("RepeatOrder", "File cannot be tested, the complete order has to be repeated. This code is returned in the event of a malfunction during the signature check, e.g. not enough storage space."),
	DS19("ElectronicSignatureRightsInsufficient", "The user’s rights (concerning his signature) are insufficient to execute the order"),
	DS20("Signer2CertificateRevoked", "The certificate is revoked for the second signer."),
	DS21("Signer2CertificateNotValid", "The certificate is not valid (revoked or not active) for the second signer."),
	DS22("IncorrectSigner2Certificate", "The certificate is not present for the second signer."),
	DS23("SignerCertificationAuthoritySigner2NotValid", "The authority of signer certification sending the certificate is unknown for the second signer."),
	DS24("WaitingTimeExpired", "Waiting time expired due to incomplete order"),
	DS25("OrderFileDeleted", "The order file was deleted by the bank server"),
	DS26("UserSignedMultipleTimes", "The same user has signed multiple times"),
	DS27("UserNotYetActivated", "The user is not yet activated (technically)"),
	DT01("InvalidDate", "Invalid date (eg, wrong or missing settlement date)"),
	DT02("InvalidCreationDate", "Invalid creation date and time in Group Header (eg, historic date)"),
	DT03("InvalidNonProcessingDate", "Invalid non bank processing date (eg, weekend or local public holiday)"),
	DT04("FutureDateNotSupported", "Future date not supported"),
	DT05("InvalidCutOffDate", "Associated message, payment information block or transaction was received after agreed processing cut-off date, i.e., date in the past."),
	DT06("ExecutionDateChanged", "Execution Date has been modified in order for transaction to be processed"),
	DU01("DuplicateMessageID", "Message Identification is not unique."),
	DU02("DuplicatePaymentInformationID", "Payment Information Block is not unique."),
	DU03("DuplicateTransaction", "Transaction is not unique."),
	DU04("DuplicateEndToEndID", "End To End ID is not unique."),
	DU05("DuplicateInstructionID", "Instruction ID is not unique."),
	DUPL("DuplicatePayment", "Payment is a duplicate of another payment"),
	ED01("CorrespondentBankNotPossible", "Correspondent bank not possible."),
	ED03("BalanceInfoRequest", "Balance of payments complementary info is requested"),
	ED05("SettlementFailed", "Settlement of the transaction has failed."),
	ED06("SettlementSystemNotAvailable", "Interbank settlement system not available."),
	EDTL("ExpiryDateTooLong", "Expiry date time of the request-to-pay is too far in the future."),
	EDTR("ExpiryDateTimeReached", "Expiry date time of the request-to-pay is already reached."),
	ERIN("ERIOptionNotSupported", "Extended Remittance Information (ERI) option is not supported."),
	FF01("InvalidFileFormat", "File Format incomplete or invalid"),
	FF02("SyntaxError", "Syntax error reason is provided as narrative information in the additional reason information."),
	FF03("InvalidPaymentTypeInformation", "Payment Type Information is missing or invalid."),
	FF04("InvalidServiceLevelCode", "Service Level code is missing or invalid"),
	FF05("InvalidLocalInstrumentCode", "Local Instrument code is missing or invalid"),
	FF06("InvalidCategoryPurposeCode", "Category Purpose code is missing or invalid"),
	FF07("InvalidPurpose", "Purpose is missing or invalid"),
	FF08("InvalidEndToEndId", "End to End Id missing or invalid"),
	FF09("InvalidChequeNumber", "Cheque number missing or invalid"),
	FF10("BankSystemProcessingError", "File or transaction cannot be processed due to technical issues at the bank side"),
	FF11("ClearingRequestAborted", "Clearing request rejected due it being subject to an abort operation."),
	FF12("OriginalTransactionNotEligibleForRequestedReturn", "Original payment is not eligible to be returned given its current status."),
	FF13("RequestForCancellationNotFound", "No record of request for cancellation found."),
	FOCR("FollowingCancellationRequest", "Return following a cancellation request."),
	FR01("Fraud", "Returned as a result of fraud."),
	FRAD("FraudulentOrigin", "Cancellation requested following a transaction that was originated fraudulently. The use of the FraudulentOrigin code should be governed by jurisdictions."),
	G000("PaymentTransferredAndTracked", "In an FI To FI Customer Credit Transfer: The Status Originator transferred the payment to the next Agent or to a Market Infrastructure. The payment transfer is tracked. No further updates will follow from the Status Originator."),
	G001("PaymentTransferredAndNotTracked", "In an FI To FI Customer Credit Transfer: The Status Originator transferred the payment to the next Agent or to a Market Infrastructure. The payment transfer is not tracked. No further updates will follow from the Status Originator."),
	G002("CreditDebitNotConfirmed", "In a FIToFI Customer Credit Transfer: Credit to the creditor’s account may not be confirmed same day. Update will follow from the Status Originator."),
	G003("CreditPendingDocuments", "In a FIToFI Customer Credit Transfer: Credit to creditor’s account is pending receipt of required documents. The Status Originator has requested creditor to provide additional documentation. Update will follow from the Status Originator."),
	G004("CreditPendingFunds", "In a FIToFI Customer Credit Transfer: Credit to the creditor’s account is pending, status Originator is waiting for funds provided via a cover. Update will follow from the Status Originator."),
	G005("DeliveredWithServiceLevel", "Payment has been delivered to creditor agent with service level."),
	G006("DeliveredWIthoutServiceLevel", "Payment has been delivered to creditor agent without service level."),
	ID01("CorrespondingOriginalFileStillNotSent", "Signature file was sent to the bank but the corresponding original file has not been sent yet."),
	IEDT("IncorrectExpiryDateTime", "Expiry date time of the request-to-pay is incorrect."),
	IRNR("InitialRTPNeverReceived", "No initial request-to-pay has been received."),
	MD01("NoMandate", "No Mandate"),
	MD02("MissingMandatoryInformationInMandate", "Mandate related information data required by the scheme is missing."),
	MD05("CollectionNotDue", "Creditor or creditor's agent should not have collected the direct debit"),
	MD06("RefundRequestByEndCustomer", "Return of funds requested by end customer"),
	MD07("EndCustomerDeceased", "End customer is deceased."),
	MS02("NotSpecifiedReasonCustomerGenerated", "Reason has not been specified by end customer"),
	MS03("NotSpecifiedReasonAgentGenerated", "Reason has not been specified by agent."),
	NARR("Narrative", "Reason is provided as narrative information in the additional reason information."),
	NERI("NoERI", "Credit transfer is tagged as an Extended Remittance Information (ERI) transaction but does not contain ERI."),
	NOAR("NonAgreedRTP", "No existing agreement for receiving request-to-pay messages."),
	NOAS("NoAnswerFromCustomer", "No response from Beneficiary."),
	NOCM("NotCompliantGeneric", "Customer account is not compliant with regulatory requirements, for example FICA (in South Africa) or any other regulatory requirements which render an account inactive for certain processing."),
	NOPG("NoPaymentGuarantee", "Requested payment guarantee (by Creditor) related to a request-to-pay cannot be provided."),
	NRCH("PayerOrPayerRTPSPNotReachable", "Recipient side of the request-to-pay (payer or its request-to-pay service provider) is not reachable."),
	PINS("TypeOfPaymentInstrumentNotSupported", "Type of payment requested in the request-to-pay is not supported by the payer."),
	RC01("BankIdentifierIncorrect", "Bank identifier code specified in the message has an incorrect format (formerly IncorrectFormatForRoutingCode)."),
	RC02("InvalidBankIdentifier", "Bank identifier is invalid or missing."),
	RC03("InvalidDebtorBankIdentifier", "Debtor bank identifier is invalid or missing"),
	RC04("InvalidCreditorBankIdentifier", "Creditor bank identifier is invalid or missing"),
	RC05("InvalidBICIdentifier", "BIC identifier is invalid or missing."),
	RC06("InvalidDebtorBICIdentifier", "Debtor BIC identifier is invalid or missing"),
	RC07("InvalidCreditorBICIdentifier", "Creditor BIC identifier is invalid or missing"),
	RC08("InvalidClearingSystemMemberIdentifier", "ClearingSystemMemberidentifier is invalid or missing."),
	RC09("InvalidDebtorClearingSystemMemberIdentifier", "Debtor ClearingSystemMember identifier is invalid or missing"),
	RC10("InvalidCreditorClearingSystemMemberIdentifier", "Creditor ClearingSystemMember identifier is invalid or missing"),
	RC11("InvalidIntermediaryAgent", "Intermediary Agent is invalid or missing"),
	RC12("MissingCreditorSchemeId", "Creditor Scheme Id is invalid or  missing"),
	RCON("RMessageConflict", "Conflict with R-Message"),
	RECI("ReceiverCustomerInformation", "Further information regarding the intended recipient."),
	REPR("RTPReceivedCanBeProcessed", "Request-to-pay has been received and can be processed further."),
	RF01("NotUniqueTransactionReference", "Transaction reference is not unique within the message."),
	RR01("MissingDebtorAccountOrIdentification", "Specification of the debtor’s account or unique identification needed for reasons of regulatory requirements is insufficient or missing"),
	RR02("MissingDebtorNameOrAddress", "Specification of the debtor’s name and/or address needed for regulatory requirements is insufficient or missing."),
	RR03("MissingCreditorNameOrAddress", "Specification of the creditor’s name and/or address needed for regulatory requirements is insufficient or missing."),
	RR04("RegulatoryReason", "Regulatory Reason"),
	RR05("RegulatoryInformationInvalid", "Regulatory or Central Bank Reporting information missing, incomplete or invalid."),
	RR06("TaxInformationInvalid", "Tax information missing, incomplete or invalid."),
	RR07("RemittanceInformationInvalid", "Remittance information structure does not comply with rules for payment type."),
	RR08("RemittanceInformationTruncated", "Remittance information truncated to comply with rules for payment type."),
	RR09("InvalidStructuredCreditorReference", "Structured creditor reference invalid or missing."),
	RR10("InvalidCharacterSet", "Character set supplied not valid for the country and payment type."),
	RR11("InvalidDebtorAgentServiceID", "Invalid or missing identification of a bank proprietary service."),
	RR12("InvalidPartyID", "Invalid or missing identification required within a particular country or payment type."),
	RTNS("RTPNotSupportedForDebtor", "Debtor does not support request-to-pay transactions."),
	RUTA("ReturnUponUnableToApply", "Return following investigation request and no remediation possible."),
	S000("ValidRequestForCancellationAcknowledged", "Request for Cancellation is acknowledged following validation."),
	S001("UETRFlaggedForCancellation", "Unique End-to-end Transaction Reference (UETR) relating to a payment has been identified as being associated with a Request for Cancellation."),
	S002("NetworkStopOfUETR", "Unique End-to-end Transaction Reference (UETR) relating to a payment has been prevent from traveling across a messaging network."),
	S003("RequestForCancellationForwarded", "Request for Cancellation has been forwarded to the payment processing/last payment processing agent."),
	S004("RequestForCancellationDeliveryAcknowledgement", "Request for Cancellation has been acknowledged as delivered to payment processing/last payment processing agent."),
	SL01("SpecificServiceOfferedByDebtorAgent", "Due to specific service offered by the Debtor Agent."),
	SL02("SpecificServiceOfferedByCreditorAgent", "Due to specific service offered by the Creditor Agent."),
	SL03("ServiceofClearingSystem", "Due to a specific service offered by the clearing system."),
	SL11("CreditorNotOnWhitelistOfDebtor", "Whitelisting service offered by the Debtor Agent; Debtor has not included the Creditor on its “Whitelist” (yet). In the Whitelist the Debtor may list all allowed Creditors to debit Debtor bank account."),
	SL12("CreditorOnBlacklistOfDebtor", "Blacklisting service offered by the Debtor Agent; Debtor included the Creditor on his “Blacklist”. In the Blacklist the Debtor may list all Creditors not allowed to debit Debtor bank account."),
	SL13("MaximumNumberOfDirectDebitTransactionsExceeded", "Due to Maximum allowed Direct Debit Transactions per period service offered by the Debtor Agent."),
	SL14("MaximumDirectDebitTransactionAmountExceeded", "Due to Maximum allowed Direct Debit Transaction amount service offered by the Debtor Agent."),
	SPII("RTPServiceProviderIdentifierIncorrect", "Identifier of the request-to-pay service provider is incorrect."),
	TA01("TransmissonAborted", "The transmission of the file was not successful – it had to be aborted (for technical reasons)"),
	TD01("NoDataAvailable", "There is no data available (for download)"),
	TD02("FileNonReadable", "The file cannot be read (e.g. unknown format)"),
	TD03("IncorrectFileStructure", "The file format is incomplete or invalid"),
	TK01("TokenInvalid", "Token is invalid."),
	TK02("SenderTokenNotFound", "Token used for the sender does not exist."),
	TK03("ReceiverTokenNotFound", "Token used for the receiver does not exist."),
	TK09("TokenMissing", "Token required for request is missing."),
	TKCM("TokenCounterpartyMismatch", "Token found with counterparty mismatch."),
	TKSG("TokenSingleUse", "Single Use Token already used."),
	TKSP("TokenSuspended", "Token found with suspended status."),
	TKVE("TokenValueLimitExceeded", "Token found with value limit rule violation."),
	TKXP("TokenExpired", "Token expired."),
	TM01("InvalidCutOffTime", "Associated message, payment information block, or transaction was received after agreed processing cut-off time."),
	TS01("TransmissionSuccessful", "The (technical) transmission of the file was successful."),
	TS04("TransferToSignByHand", "The order was transferred to pass by accompanying note signed by hand"),
	UCRD("UnknownCreditor", "Unknown Creditor."),
	UPAY("UnduePayment", "Payment is not justified."),
}

enum class ExternalPaymentGroupStatusCode(val isoCode: String, val description: String) {
	ACCC("AcceptedSettlementCompletedCreditorAccount", "Settlement on the creditor's account has been completed."),
	ACCP("AcceptedCustomerProfile", "Preceding check of technical validation was successful. Customer profile check was also successful."),
	ACSC("AcceptedSettlementCompletedDebitorAccount", "Settlement on the debtor's account has been completed."),
	ACSP("AcceptedSettlementInProcess", "All preceding checks such as technical validation and customer profile were successful and therefore the payment initiation has been accepted for execution."),
	ACTC("AcceptedTechnicalValidation", "Authentication and syntactical and semantical validation are successful"),
	ACWC("AcceptedWithChange", "Instruction is accepted but a change will be made, such as date or remittance not sent."),
	PART("PartiallyAccepted", "A number of transactions have been accepted, whereas another number of transactions have not yet achieved"),
	PDNG("Pending", "Payment initiation or individual transaction included in the payment initiation is pending. Further checks and status update will be performed."),
	RCVD("Received", "Payment initiation has been received by the receiving agent"),
	RJCT("Rejected", "Payment initiation or individual transaction included in the payment initiation has been rejected."),
}

enum class ExternalPaymentTransactionStatusCode(val isoCode: String, val description: String) {
	ACCC("AcceptedSettlementCompletedCreditorAccount", "Settlement on the creditor's account has been completed."),
	ACCP("AcceptedCustomerProfile", "Preceding check of technical validation was successful. Customer profile check was also successful."),
	ACFC("AcceptedFundsChecked", "Preceding check of technical validation and customer profile was successful and an automatic funds check was positive."),
	ACIS("AcceptedandChequeIssued", "Payment instruction to issue a cheque has been accepted, and the cheque has been issued but not yet been deposited or cleared."),
	ACPD("AcceptedClearingProcessed", "Status of transaction released from the Debtor Agent and accepted by the clearing."),
	ACSC("AcceptedSettlementCompletedDebitorAccount", "Settlement completed."),
	ACSP("AcceptedSettlementInProcess", "All preceding checks such as technical validation and customer profile were successful and therefore the payment instruction has been accepted for execution."),
	ACTC("AcceptedTechnicalValidation", "Authentication and syntactical and semantical validation are successful"),
	ACWC("AcceptedWithChange", "Instruction is accepted but a change will be made, such as date or remittance not sent."),
	ACWP("AcceptedWithoutPosting", "Payment instruction included in the credit transfer is accepted without being posted to the creditor customer’s account."),
	BLCK("Blocked", "Payment transaction previously reported with status 'ACWP' is blocked, for example, funds will neither be posted to the Creditor's account, nor be returned to the Debtor."),
	CANC("Cancelled", "Payment initiation has been successfully cancelled after having received a request for cancellation."),
	CPUC("CashPickedUpByCreditor", "Cash has been picked up by the Creditor."),
	PATC("PartiallyAcceptedTechnicalCorrect", "Payment initiation needs multiple authentications, where some but not yet all have been performed. Syntactical and semantical validations are successful."),
	PDNG("Pending", "Payment instruction is pending. Further checks and status update will be performed."),
	PRES("Presented", "Request for Payment has been presented to the Debtor."),
	RCVD("Received", "Payment instruction has been received."),
	RJCT("Rejected", "Payment instruction has been rejected."),
}