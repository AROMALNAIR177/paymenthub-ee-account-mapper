package org.mifos.pheeidaccountvalidatorimpl.gsmaconnector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.mifos.pheeidaccountvalidatorimpl.service.AccountValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service(value = "gsma")
public class GSMAAccountValidation extends AccountValidationService {

    @Value("${gsma-connector.contactpoint}")
    public String gsmaConnectorContactPoint;
    @Value("${gsma-connector.endpoint.account-status}")
    public String accountStatusEndpoint;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GSMAAccountValidation(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /** Keeps the old behaviour: the status code is read below, so a 4xx or 5xx must not throw here. */
    private static void keepTheResponse(HttpRequest request, ClientHttpResponse response) {
        // deliberately nothing
    }

    @Override
    public Boolean validateAccount(String financialAddress, String tenant, String paymentModality, String payeeIdentity,
            String callbackURL) {
        // Fill a local copy: writing the result back into the singleton's template removed the placeholders after the
        // first call, so every later lookup queried the first identity's account.
        String endpoint = accountStatusEndpoint.replace("identifierType", paymentModality).replace("identifierId", payeeIdentity);

        ResponseEntity<String> response = restClient.get().uri(URI.create(gsmaConnectorContactPoint + endpoint))
                .header("Platform-TenantId", tenant).retrieve()
                .onStatus(status -> true, GSMAAccountValidation::keepTheResponse).toEntity(String.class);

        if (response.getStatusCode().value() != 200) {
            return false;
        }
        Map<String, Object> responseMap;
        try {
            responseMap = objectMapper.readValue(response.getBody(), Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        // The unused `responseMap.get("fieldName").toString()` that stood here threw a NullPointerException on every
        // call (the AMS account-status response has no such field), so this check was never reached.
        return "savingsAccountStatusType.active".equals(responseMap.get("accountStatus"));
    }
}
