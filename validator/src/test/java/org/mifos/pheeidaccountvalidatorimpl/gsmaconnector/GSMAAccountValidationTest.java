package org.mifos.pheeidaccountvalidatorimpl.gsmaconnector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * A stub AMS account-status endpoint, answering every request with the given body and recording the paths it was
 * asked for.
 */
class GSMAAccountValidationTest {

    private HttpServer server;
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private volatile String responseBody = "{\"accountStatus\":\"savingsAccountStatusType.active\"}";
    private GSMAAccountValidation validation;

    @BeforeEach
    void startStubAms() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        validation = new GSMAAccountValidation(RestClient.create(), new ObjectMapper());
        validation.gsmaConnectorContactPoint = "http://127.0.0.1:" + server.getAddress().getPort();
        validation.accountStatusEndpoint = "/ams/accounts/identifierType/identifierId/status";
    }

    @AfterEach
    void stopStubAms() {
        server.stop(0);
    }

    /** PHEE-431: the template was overwritten on the first call, so call 2 for bob asked for alice's account. */
    @Test
    void eachLookupQueriesItsOwnIdentity() {
        validateIgnoringOutcome("alice");
        validateIgnoringOutcome("bob");

        assertThat(requestedPaths).containsExactly("/ams/accounts/MSISDN/alice/status", "/ams/accounts/MSISDN/bob/status");
    }

    private void validateIgnoringOutcome(String payeeIdentity) {
        try {
            validation.validateAccount("acc-1", "greenbank", "MSISDN", payeeIdentity, "http://callback");
        } catch (RuntimeException e) {
            // the outcome is covered separately; this test is about which account was queried
        }
    }
}
