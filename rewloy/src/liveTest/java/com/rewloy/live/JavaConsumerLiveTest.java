package com.rewloy.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rewloy.RequestOptions;
import com.rewloy.Rewloy;
import com.rewloy.RewloyException;
import com.rewloy.RewloyOptions;
import com.rewloy.RewloyResponse;
import com.rewloy.models.GetBusinessData;
import com.rewloy.models.GetMetaData;
import com.rewloy.models.GetPassData;
import com.rewloy.models.IssuePassBody;
import com.rewloy.models.IssuePassData;
import com.rewloy.models.ListPassOperationsItem;
import com.rewloy.models.ListProgramsItem;
import com.rewloy.models.PassActionBody;
import com.rewloy.models.PassActionData;
import com.rewloy.models.PassActionDataOption1;
import com.rewloy.models.RecordSaleBody;
import com.rewloy.models.RecordSaleData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The library called from Java, as a Java POS or back office would: the builder, the setters, the blocking calls,
 * the unchecked exception, the sealed answer and the loop over a paged list.
 */
@ExtendWith(LiveGuard.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Order(13)
@DisplayName("java consumer")
class JavaConsumerLiveTest {
    private Rewloy client() {
        Live.guard();
        return new Rewloy(RewloyOptions.builder().apiKey(Live.apiKey).baseUrl(Live.baseUrl).build());
    }

    @Test
    @Order(1)
    void metaBusinessAndPrograms() {
        Rewloy rewloy = client();
        GetMetaData meta = rewloy.getMeta();
        assertEquals("v1", meta.getApiVersion());
        assertEquals("dev", meta.getAdditionalProperties().get("environment").asString());
        RewloyResponse<GetBusinessData> business = rewloy.getBusinessWithResponse();
        assertTrue(business.isTestMode());
        assertTrue(business.getData().getName().endsWith("· Test"));
        boolean found = false;
        for (ListProgramsItem p : rewloy.listPrograms()) {
            if (p.getId().equals(Live.INSTANCE.getStampProgramId())) found = true;
        }
        assertTrue(found);
    }

    @Test
    @Order(2)
    void issueSellActAndReadOperations() {
        Rewloy rewloy = client();
        IssuePassBody body = new IssuePassBody(Live.INSTANCE.getStampProgramId());
        body.setEmail(Live.INSTANCE.email("java"));
        body.setFirstName("Java");
        body.setKvkkConsent(true);
        IssuePassData issued = rewloy.issuePass(body);
        assertTrue(issued.getCreated());
        String serial = issued.getSerial();

        GetPassData card = rewloy.getPass(serial);
        assertEquals("stamp", card.getType());
        assertEquals(0, card.getStamps().getCount());

        String saleKey = Live.INSTANCE.key("java-sale");
        RecordSaleBody sale = new RecordSaleBody(2500, Live.INSTANCE.getLocationId(), "fis-java", null, null);
        RecordSaleData sold = rewloy.recordSale(serial, sale, RequestOptions.builder().idempotencyKey(saleKey).build());
        assertEquals("stamps", sold.getApplied());
        assertFalse(sold.getDuplicate());

        PassActionBody earn = new PassActionBody("earn-stamps", Live.INSTANCE.getLocationId());
        earn.setCount(2);
        PassActionData acted = rewloy.passAction(serial, earn, RequestOptions.withIdempotencyKey(Live.INSTANCE.key("java-earn")));
        assertTrue(acted instanceof PassActionDataOption1);
        assertEquals(3.0, ((PassActionDataOption1) acted).getBalance());

        int operations = 0;
        for (ListPassOperationsItem op : rewloy.listPassOperationsAll(serial)) {
            operations++;
            assertNotNull(op.getKind());
        }
        assertEquals(2, operations);
    }

    @Test
    @Order(3)
    void errorsAreUncheckedExceptions() {
        Rewloy rewloy = client();
        RewloyException e = assertThrows(RewloyException.class, () -> rewloy.getPass("ZZZZ-ZZZZ-ZZZZ"));
        assertEquals(404, e.getStatus());
        assertEquals("PASS_NOT_FOUND", e.getCode());
        assertNotNull(e.getRequestId());
    }
}
