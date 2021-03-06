package org.prebid.server.it;

import io.restassured.response.Response;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static java.util.Collections.singletonList;

/**
 * Algorix Test in org.prebid.server.it
 */
@RunWith(SpringRunner.class)
public class AlgorixTest extends IntegrationTest {

    @Test
    public void openrtb2AuctionShouldRespondWithBidsFromAlgorix() throws IOException, JSONException {
        // given
        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/algorix-exchange"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", equalTo("application/json;charset=UTF-8"))
                .withHeader("x-openrtb-version", equalTo("2.5"))
                .withRequestBody(equalToJson(jsonFrom("openrtb2/algorix/test-algorix-bid-request.json")))
                .willReturn(aResponse().withBody(jsonFrom("openrtb2/algorix/test-algorix-bid-response.json"))));

        // pre-bid cache
        WIRE_MOCK_RULE.stubFor(post(urlPathEqualTo("/cache"))
                .withRequestBody(equalToJson(jsonFrom("openrtb2/algorix/test-cache-algorix-request.json")))
                .willReturn(aResponse().withBody(jsonFrom("openrtb2/algorix/test-cache-algorix-response.json"))));

        // when
        final Response response = given(SPEC)
                .header("Referer", "http://www.example.com")
                .header("X-Forwarded-For", "193.168.244.1")
                .header("User-Agent", "userAgent")
                .header("Origin", "http://www.example.com")
                // this uids cookie value stands for {"uids":{"algorix":"BTW-UID"}}
                .cookie("uids", "eyJ1aWRzIjp7ImFsZ29yaXgiOiJCVFctVUlEIn19")
                .body(jsonFrom("openrtb2/algorix/test-auction-algorix-request.json"))
                .post("/openrtb2/auction");
        // then
        final String expectedAuctionResponse = openrtbAuctionResponseFrom(
                "openrtb2/algorix/test-auction-algorix-response.json",
                response, singletonList("algorix"));

        JSONAssert.assertEquals(expectedAuctionResponse, response.asString(), JSONCompareMode.NON_EXTENSIBLE);
    }
}
