package org.mifos.identityaccountmapper.api.implementation;

import static org.mifos.identityaccountmapper.zeebe.ZeebeVariables.ACCOUNT_LOOKUP;
import static org.mifos.identityaccountmapper.zeebe.ZeebeVariables.ACCOUNT_LOOKUP_FAILED;
import static org.mifos.identityaccountmapper.zeebe.ZeebeVariables.BATCH_ACCOUNT_LOOKUP_RESPONSE;
import static org.mifos.identityaccountmapper.zeebe.ZeebeVariables.PARTY_LOOKUP_FSP_ID;
import static org.mifos.identityaccountmapper.zeebe.ZeebeVariables.PAYEE_PARTY_ID;
import static org.mifos.identityaccountmapper.zeebe.ZeebeVariables.PAYEE_PARTY_ID_TYPE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.mifos.identityaccountmapper.api.definition.AccountLookupCallback;
import org.mifos.identityaccountmapper.data.AccountLookupResponseDTO;
import org.mifos.identityaccountmapper.data.BatchAccountLookupResponseDTO;
import org.mifos.identityaccountmapper.data.PaymentModalityDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class AccountLookupCallbackController implements AccountLookupCallback {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired(required = false)
    private ZeebeClient zeebeClient;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public ResponseEntity<Object> accountLookupCallback(String requestBody)
            throws ExecutionException, InterruptedException, JsonProcessingException {
        Map<String, Object> variables = new HashMap<>();
        AccountLookupResponseDTO accountLookupResponseDTO;
        try {
            logger.info(requestBody);
            accountLookupResponseDTO = objectMapper.readValue(requestBody, AccountLookupResponseDTO.class);
        } catch (IOException e) {
            // without a requestId there is nothing to correlate the answer with
            logger.error("Unparseable account lookup callback, not published: {}", requestBody, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Unparseable account lookup callback");
        }
        String transactionId = accountLookupResponseDTO.getRequestId();
        if (transactionId == null) {
            logger.error("Account lookup callback without requestId, not published: {}", requestBody);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Account lookup callback without requestId");
        }

        List<PaymentModalityDTO> paymentModalities = accountLookupResponseDTO.getPaymentModalityList();
        boolean validated = Boolean.TRUE.equals(accountLookupResponseDTO.getIsValidated());
        if (paymentModalities != null && !paymentModalities.isEmpty()) {
            PaymentModalityDTO paymentModality = paymentModalities.get(0);
            variables.put(PAYEE_PARTY_ID, paymentModality.getFinancialAddress());
            variables.put(PAYEE_PARTY_ID_TYPE, paymentModality.getPaymentModality());
            variables.put(PARTY_LOOKUP_FSP_ID, paymentModality.getBankingInstitutionCode());
        } else {
            logger.warn("Account lookup callback for {} has no payment modality", transactionId);
            validated = false;
        }
        variables.put(ACCOUNT_LOOKUP_FAILED, !validated);
        log.info("variables set: " + variables);

        return publish(ACCOUNT_LOOKUP, transactionId, variables);
    }

    @Override
    public ResponseEntity<Object> batchAccountLookupCallback(String requestBody)
            throws ExecutionException, InterruptedException, JsonProcessingException {
        Map<String, Object> variables = new HashMap<>();
        BatchAccountLookupResponseDTO batchAccountLookupResponseDTO;
        try {
            batchAccountLookupResponseDTO = objectMapper.readValue(requestBody, BatchAccountLookupResponseDTO.class);
        } catch (IOException e) {
            logger.error("Unparseable batch account lookup callback, not published: {}", requestBody, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Unparseable batch account lookup callback");
        }
        String transactionId = batchAccountLookupResponseDTO.getRequestID();
        if (transactionId == null) {
            logger.error("Batch account lookup callback without requestID, not published: {}", requestBody);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Batch account lookup callback without requestID");
        }
        variables.put("batchAccountLookupCallback", requestBody);
        variables.put("cachedTransactionId", transactionId);

        return publish(BATCH_ACCOUNT_LOOKUP_RESPONSE, transactionId, variables);
    }

    /**
     * Publishes the lookup result and waits for Zeebe to accept it, so a failed publish is logged and answered with 500
     * instead of the callback being acknowledged while the workflow keeps waiting.
     */
    private ResponseEntity<Object> publish(String messageName, String correlationKey, Map<String, Object> variables) {
        if (zeebeClient == null) {
            log.warn("ZeebeClient is null, cannot send message!");
            return ResponseEntity.status(HttpStatus.OK).body("Accepted");
        }
        try {
            zeebeClient.newPublishMessageCommand().messageName(messageName).correlationKey(correlationKey)
                    .timeToLive(Duration.ofMillis(50000)).variables(variables).send().join();
        } catch (RuntimeException e) {
            logger.error("Failed to publish {} for {}", messageName, correlationKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to publish " + messageName);
        }
        return ResponseEntity.status(HttpStatus.OK).body("Accepted");
    }
}
