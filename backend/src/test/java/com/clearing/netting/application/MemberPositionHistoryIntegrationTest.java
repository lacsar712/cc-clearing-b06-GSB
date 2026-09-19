package com.clearing.netting.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-stack acceptance test (H2 + real JPA + real JWT filter), driven entirely over HTTP.
 * Mirrors the seed layout so the acceptance criteria are verified end-to-end:
 * multi-batch same-day same-currency aggregation, source traceability into run detail,
 * empty table for a member without history, and viewer (read-only) access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemberPositionHistoryIntegrationTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 22);
    private static final LocalDate D2 = D.plusDays(1);

    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void historyAggregatesAcrossBatchesAndTracesToRunDetail() throws Exception {
        String operator = login("operator", "op123456");
        String a = createMember(operator, "Alpha Bank");
        String b = createMember(operator, "Beta Securities");
        String c = createMember(operator, "Gamma Clearing");
        String delta = createMember(operator, "Delta Holdings");

        // Batch A (D/USD): A -85000, B +40000, C +45000 — then SETTLED.
        obligation(operator, a, b, "USD", "100000", D, D);
        obligation(operator, b, c, "USD", "60000", D, D);
        obligation(operator, c, a, "USD", "40000", D, D);
        obligation(operator, a, c, "USD", "25000", D, D);
        String runA = executeNetting(operator, D, "USD");
        settle(operator, runA);

        // Batch B (same D/USD): A +20000, B -30000, C +10000 — COMPLETED, not settled.
        obligation(operator, b, a, "USD", "30000", D, D);
        obligation(operator, a, c, "USD", "10000", D, D);
        String runB = executeNetting(operator, D, "USD");

        // Batch C (D+1/EUR): A +2000, B +3000, C -5000.
        obligation(operator, c, b, "EUR", "5000", D, D2);
        obligation(operator, b, a, "EUR", "2000", D, D2);
        String runC = executeNetting(operator, D2, "EUR");

        JsonNode history = getHistory(operator, a);
        assertEquals(a, history.path("memberId").asText());
        assertFalse(history.path("aggregationRule").asText().isBlank());
        JsonNode rows = history.path("rows");
        assertEquals(2, rows.size(), "one row per (settleDate, currency)");

        // Row 0: D/USD aggregated across two batches.
        JsonNode usd = rows.get(0);
        assertEquals(D.toString(), usd.path("settleDate").asText());
        assertEquals("USD", usd.path("currency").asText());
        assertEquals(0, new BigDecimal("-65000.00000000").compareTo(usd.path("totalNetAmount").decimalValue()));
        assertEquals(2, usd.path("sourceCount").asInt());

        BigDecimal sumSources = BigDecimal.ZERO;
        boolean sawSettledA = false;
        boolean sawUnsettledB = false;
        for (JsonNode src : usd.path("sources")) {
            sumSources = sumSources.add(src.path("netAmount").decimalValue());
            assertEquals("COMPLETED", src.path("runStatus").asText());
            // The source amount must equal this member's net position in that run's detail page.
            BigDecimal inRunDetail = memberNetInRun(operator, src.path("runId").asText(), a);
            assertEquals(0, inRunDetail.compareTo(src.path("netAmount").decimalValue()),
                    "source netAmount must match the run detail position");
            if (src.path("runId").asText().equals(runA)) {
                sawSettledA = src.path("settled").asBoolean();
            }
            if (src.path("runId").asText().equals(runB)) {
                sawUnsettledB = !src.path("settled").asBoolean();
            }
        }
        assertTrue(sawSettledA, "settled batch A must be flagged settled");
        assertTrue(sawUnsettledB, "unsettled batch B must not be flagged settled");
        assertEquals(0, new BigDecimal("-65000.00000000").compareTo(sumSources),
                "sum of source batch positions must equal the aggregate row");

        // Row 1: D+1/EUR single batch.
        JsonNode eur = rows.get(1);
        assertEquals(D2.toString(), eur.path("settleDate").asText());
        assertEquals("EUR", eur.path("currency").asText());
        assertEquals(0, new BigDecimal("2000.00000000").compareTo(eur.path("totalNetAmount").decimalValue()));
        assertEquals(1, eur.path("sourceCount").asInt());
        assertEquals(runC, eur.path("sources").get(0).path("runId").asText());
        assertFalse(eur.path("sources").get(0).path("settled").asBoolean());

        // Member without any history -> empty table, HTTP 200 (no crash).
        JsonNode empty = getHistory(operator, delta);
        assertEquals(0, empty.path("rows").size());

        // Read-only viewer can query the same data.
        String viewer = login("viewer", "view123456");
        ResponseEntity<String> viewerResp = exchange("/api/members/" + a + "/position-history", viewer, HttpMethod.GET, null);
        assertEquals(200, viewerResp.getStatusCode().value());
        assertEquals(2, json.readTree(viewerResp.getBody()).path("rows").size());

        // Unauthenticated request is rejected.
        ResponseEntity<String> anon = exchange("/api/members/" + a + "/position-history", null, HttpMethod.GET, null);
        assertEquals(401, anon.getStatusCode().value());

        // Every produced run conserved at zero.
        assertEquals(0, runSumNet(operator, runA).compareTo(BigDecimal.ZERO));
        assertEquals(0, runSumNet(operator, runB).compareTo(BigDecimal.ZERO));
        assertEquals(0, runSumNet(operator, runC).compareTo(BigDecimal.ZERO));
    }

    // ----- helpers -----

    private String base() {
        return "http://localhost:" + port;
    }

    private String login(String username, String password) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("username", username, "password", password));
        ResponseEntity<String> resp = exchangeRaw("/api/auth/login", null, HttpMethod.POST, body);
        assertEquals(200, resp.getStatusCode().value(), "login failed: " + resp.getBody());
        return json.readTree(resp.getBody()).path("token").asText();
    }

    private String createMember(String token, String name) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("name", name));
        ResponseEntity<String> resp = exchange("/api/members", token, HttpMethod.POST, body);
        assertEquals(200, resp.getStatusCode().value(), "createMember failed: " + resp.getBody());
        return json.readTree(resp.getBody()).path("memberId").asText();
    }

    private void obligation(String token, String payer, String payee, String ccy, String amount,
                            LocalDate tradeDate, LocalDate settleDate) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "payerMemberId", payer,
                "payeeMemberId", payee,
                "currency", ccy,
                "amount", new BigDecimal(amount),
                "tradeDate", tradeDate.toString(),
                "settleDate", settleDate.toString()));
        ResponseEntity<String> resp = exchange("/api/obligations", token, HttpMethod.POST, body);
        assertEquals(200, resp.getStatusCode().value(), "obligation create failed: " + resp.getBody());
    }

    private String executeNetting(String token, LocalDate settleDate, String ccy) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("settleDate", settleDate.toString(), "currency", ccy));
        ResponseEntity<String> resp = exchange("/api/netting-runs", token, HttpMethod.POST, body);
        assertEquals(200, resp.getStatusCode().value(), "netting execute failed: " + resp.getBody());
        JsonNode node = json.readTree(resp.getBody());
        assertEquals("COMPLETED", node.path("run").path("status").asText());
        return node.path("run").path("runId").asText();
    }

    private void settle(String token, String runId) {
        ResponseEntity<String> resp = exchange("/api/netting-runs/" + runId + "/settle", token, HttpMethod.POST, null);
        assertEquals(200, resp.getStatusCode().value(), "settle failed: " + resp.getBody());
    }

    private JsonNode getHistory(String token, String memberId) throws Exception {
        ResponseEntity<String> resp = exchange("/api/members/" + memberId + "/position-history", token, HttpMethod.GET, null);
        assertEquals(200, resp.getStatusCode().value(), "position-history failed: " + resp.getBody());
        JsonNode node = json.readTree(resp.getBody());
        assertNotNull(node);
        return node;
    }

    private BigDecimal memberNetInRun(String token, String runId, String memberId) throws Exception {
        ResponseEntity<String> resp = exchange("/api/netting-runs/" + runId, token, HttpMethod.GET, null);
        assertEquals(200, resp.getStatusCode().value());
        JsonNode positions = json.readTree(resp.getBody()).path("positions");
        for (JsonNode p : positions) {
            if (p.path("memberId").asText().equals(memberId)) {
                return p.path("netAmount").decimalValue();
            }
        }
        throw new AssertionError("member " + memberId + " has no position in run " + runId);
    }

    private BigDecimal runSumNet(String token, String runId) throws Exception {
        ResponseEntity<String> resp = exchange("/api/netting-runs/" + runId, token, HttpMethod.GET, null);
        assertEquals(200, resp.getStatusCode().value());
        return json.readTree(resp.getBody()).path("sumNetAmount").decimalValue();
    }

    private ResponseEntity<String> exchange(String path, String token, HttpMethod method, String body) {
        return exchangeRaw(path, token, method, body);
    }

    private ResponseEntity<String> exchangeRaw(String path, String token, HttpMethod method, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return rest.exchange(base() + path, method, entity, String.class);
    }
}
