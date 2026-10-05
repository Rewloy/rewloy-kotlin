package com.rewloy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rewloy.json.JsonValue;
import com.rewloy.models.GetPassData;
import com.rewloy.models.ListCustomersItem;
import com.rewloy.models.ListCustomersQuery;
import com.rewloy.models.PassActionBody;
import com.rewloy.models.PassActionData;
import com.rewloy.models.PassActionDataOption1;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a Java caller sees: builders, overloads, plain getters, unchecked exceptions, for-each over pages and streams. */
class JavaUsageTest {
    private Rewloy client(StubServer server) {
        return new Rewloy(RewloyOptions.builder().apiKey("rwk_abc").baseUrl(server.getUrl()).maxRetries(0).build());
    }

    @Test
    void callsAnOperationAndReadsTheAnswerWithGetters() {
        try (StubServer server = new StubServer()) {
            server.enqueue(Answers.json(200, Fixtures.PASS));
            GetPassData card = client(server).getPass("ABCD-EFGH-JKLM");
            assertEquals("ABCD-EFGH-JKLM", card.getSerial());
            assertEquals("stamp", card.getType());
            assertEquals(Double.valueOf(3), card.getBalance());
            assertEquals(false, card.getRewardReady());
            assertEquals(0, card.getRewardsReady());
        }
    }

    @Test
    void buildsABodyWithItsRequiredFieldsAndSettersAndSendsAnIdempotencyKey() {
        try (StubServer server = new StubServer()) {
            server.enqueue(Answers.json(200, Fixtures.ACTION));
            PassActionBody body = new PassActionBody("earn-stamps", "loc-1");
            body.setCount(1);
            RequestOptions options = RequestOptions.builder().idempotencyKey("fis-000001").build();
            PassActionData result = client(server).passAction("ABCD-EFGH-JKLM", body, options);
            // A sealed class: a Java caller reads what both shapes share, or checks the shape with instanceof.
            assertEquals(false, result.getDuplicate());
            assertTrue(result instanceof PassActionDataOption1);
            assertEquals(5.0, ((PassActionDataOption1) result).getBalance());
            assertEquals("fis-000001", server.getReceived().get(0).header("idempotency-key"));
            assertEquals("{\"action\":\"earn-stamps\",\"locationId\":\"loc-1\",\"count\":1}", server.getReceived().get(0).getBody());
        }
    }

    @Test
    void failuresAreUncheckedRewloyExceptions() {
        try (StubServer server = new StubServer()) {
            server.enqueue(Answers.json(404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"yok\"}}"));
            RewloyException e = assertThrows(RewloyException.class, () -> client(server).getPass("X"));
            assertEquals(404, e.getStatus());
            assertEquals(ErrorCode.NOT_FOUND, e.getCode());
            assertEquals("yok", e.getDetail());
        }
    }

    @Test
    void walksPagesWithForEach() {
        try (StubServer server = new StubServer()) {
            server.enqueue(Answers.json(200, Fixtures.INSTANCE.customers(1, 2, 1, 2, 3)), Answers.json(200, Fixtures.INSTANCE.customers(3, 1, 2, 2, 3)));
            ListCustomersQuery query = new ListCustomersQuery();
            query.setLimit(2);
            List<String> ids = new ArrayList<>();
            for (ListCustomersItem customer : client(server).listCustomersAll(query)) ids.add(customer.getPersonId());
            assertEquals(java.util.Arrays.asList("p1", "p2", "p3"), ids);
        }
    }

    @Test
    void readsAStreamInATryWithResources() {
        try (StubServer server = new StubServer()) {
            server.enqueue(Answers.events("event: event", "data: {\"n\":1}"));
            RequestOptions options = RequestOptions.builder().reconnect(false).build();
            List<String> seen = new ArrayList<>();
            try (EventStream feed = client(server).liveFeed(options)) {
                for (ServerSentEvent event : feed) seen.add(event.getData());
            }
            assertEquals(java.util.Arrays.asList("{\"n\":1}"), seen);
        }
    }

    @Test
    void verifiesAWebhookAndSignsOneForATest() {
        String body = "{\"type\":\"webhook.test\",\"created_at\":\"2026-10-03T12:00:00.000Z\",\"data\":{\"message\":\"x\"}}";
        String header = Webhook.sign(body, "whsec_abc");
        WebhookEvent event = Webhook.verify(body, header, "whsec_abc");
        assertEquals("webhook.test", event.getType());
        WebhookSignatureException e = assertThrows(WebhookSignatureException.class, () -> Webhook.verify(body, header, "whsec_other"));
        assertEquals(WebhookFailure.MISMATCH, e.getReason());
        assertTrue(Webhook.sign(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "whsec_abc", 1790000000L).startsWith("t=1790000000,v1="));
    }

    @Test
    void jsonValuesAreUsableFromJava() {
        JsonValue v = JsonValue.parse("{\"a\":[1,\"x\"]}");
        assertEquals("x", v.asObject().get("a").asArray().get(1).asString());
        assertEquals("\"y\"", JsonValue.of("y").toString());
    }

    @Test
    void aClientWithoutAnyOptionsAndTheCancelToken() {
        Rewloy anonymous = new Rewloy();
        assertEquals(null, anonymous.getCredential());
        CancelToken token = new CancelToken();
        Rewloy scoped = anonymous.withCancel(token);
        token.cancel();
        assertTrue(token.isCancelled());
        assertThrows(java.util.concurrent.CancellationException.class, () -> scoped.getPass("X"));
    }
}
