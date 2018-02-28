package org.prebid.server.bidder.index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.BidRequest.BidRequestBuilder;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.Source;
import com.iab.openrtb.request.User;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.BidResponse.BidResponseBuilder;
import com.iab.openrtb.response.SeatBid;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.auction.model.AdUnitBid;
import org.prebid.server.auction.model.AdUnitBid.AdUnitBidBuilder;
import org.prebid.server.auction.model.AdapterRequest;
import org.prebid.server.auction.model.PreBidRequestContext;
import org.prebid.server.auction.model.PreBidRequestContext.PreBidRequestContextBuilder;
import org.prebid.server.bidder.index.proto.IndexParams;
import org.prebid.server.bidder.model.AdapterHttpRequest;
import org.prebid.server.bidder.model.ExchangeCall;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.proto.request.PreBidRequest;
import org.prebid.server.proto.request.PreBidRequest.PreBidRequestBuilder;
import org.prebid.server.proto.request.Video;
import org.prebid.server.proto.response.BidderDebug;
import org.prebid.server.proto.response.MediaType;
import org.prebid.server.usersyncer.IndexUsersyncer;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.*;
import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

public class IndexAdapterTest extends VertxTest {

    private static final String ADAPTER = "indexExchange";
    private static final String ENDPOINT_URL = "http://exchange.org/";
    private static final String USERSYNC_URL = "//usersync.org/";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private UidsCookie uidsCookie;

    private AdapterRequest adapterRequest;
    private PreBidRequestContext preBidRequestContext;
    private ExchangeCall exchangeCall;
    private IndexAdapter adapter;
    private IndexUsersyncer usersyncer;

    @Before
    public void setUp() {
        adapterRequest = givenBidder(identity());
        preBidRequestContext = givenPreBidRequestContext(identity(), identity());
        exchangeCall = givenExchangeCall(identity(), identity());
        usersyncer = new IndexUsersyncer(USERSYNC_URL);
        adapter = new IndexAdapter(usersyncer, ENDPOINT_URL);
    }

    @Test
    public void creationShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(
                () -> new IndexAdapter(null, null));
        assertThatNullPointerException().isThrownBy(
                () -> new IndexAdapter(usersyncer, null));
    }

    @Test
    public void creationShouldFailOnInvalidEndpointUrl() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IndexAdapter(usersyncer, "invalid_url"))
                .withMessage("URL supplied is not valid: invalid_url");
    }

    @Test
    public void makeHttpRequestsShouldReturnRequestsWithExpectedHeaders() {
        // when
        final List<AdapterHttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).flatExtracting(r -> r.getHeaders().entries())
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsOnly(tuple("Content-Type", "application/json;charset=utf-8"),
                        tuple("Accept", "application/json"));
    }

    @Test
    public void makeHttpRequestsShouldFailIfAppIsPresentInPreBidRequest() {
        // given
        preBidRequestContext = givenPreBidRequestContext(identity(), builder -> builder
                .app(App.builder().build()));

        // when and then
        assertThatThrownBy(() -> adapter.makeHttpRequests(adapterRequest, preBidRequestContext))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("Index doesn't support apps");
    }

    @Test
    public void makeHttpRequestsShouldFailIfParamsMissingInAtLeastOneAdUnitBid() {
        // given
        adapterRequest = AdapterRequest.of(ADAPTER, asList(
                givenAdUnitBid(identity()),
                givenAdUnitBid(builder -> builder.params(null))));

        // when and then
        assertThatThrownBy(() -> adapter.makeHttpRequests(adapterRequest, preBidRequestContext))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("IndexExchange params section is missing");
    }

    @Test
    public void makeHttpRequestsShouldFailIfAdUnitBidParamsCouldNotBeParsed() {
        // given
        final ObjectNode params = mapper.createObjectNode();
        params.set("siteID", new TextNode("non-integer"));
        adapterRequest = givenBidder(builder -> builder.params(params));

        // when and then
        assertThatThrownBy(() -> adapter.makeHttpRequests(adapterRequest, preBidRequestContext))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessageStartingWith(
                        "unmarshal params '{\"siteID\":\"non-integer\"}' failed: Cannot deserialize value of type");
    }

    @Test
    public void makeHttpRequestsShouldFailIfAdUnitBidParamPublisherIdIsMissing() {
        // given
        final ObjectNode params = mapper.createObjectNode();
        params.set("siteID", null);
        adapterRequest = givenBidder(builder -> builder.params(params));

        // when and then
        assertThatThrownBy(() -> adapter.makeHttpRequests(adapterRequest, preBidRequestContext))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("Missing siteID param");
    }

    @Test
    public void makeHttpRequestsShouldFailIfMediaTypeIsEmpty() {
        //given
        adapterRequest = AdapterRequest.of(ADAPTER, singletonList(
                givenAdUnitBid(builder -> builder
                        .adUnitCode("adUnitCode1")
                        .mediaTypes(emptySet()))));

        preBidRequestContext = givenPreBidRequestContext(identity(), identity());

        // when and then
        assertThatThrownBy(() -> adapter.makeHttpRequests(adapterRequest, preBidRequestContext))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("openRTB bids need at least one Imp");
    }

    @Test
    public void makeHttpRequestsShouldFailIfMediaTypeIsVideoAndMimesListIsEmpty() {
        //given
        adapterRequest = AdapterRequest.of(ADAPTER, singletonList(
                givenAdUnitBid(builder -> builder
                        .adUnitCode("adUnitCode1")
                        .mediaTypes(singleton(MediaType.video))
                        .video(Video.builder()
                                .mimes(emptyList())
                                .build()))));

        // when and then
        assertThatThrownBy(() -> adapter.makeHttpRequests(adapterRequest, preBidRequestContext))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("Invalid AdUnit: VIDEO media type with no video data");
    }

    @Test
    public void makeHttpRequestsShouldReturnRequestsWithExpectedFields() {
        // given
        adapterRequest = givenBidder(
                builder -> builder
                        .bidderCode(ADAPTER)
                        .adUnitCode("adUnitCode1")
                        .instl(1)
                        .topframe(1)
                        .sizes(singletonList(Format.builder().w(300).h(250).build()))
                        .params(mapper.valueToTree(IndexParams.of(486))));

        preBidRequestContext = givenPreBidRequestContext(
                builder -> builder
                        .referer("http://www.example.com")
                        .domain("example.com")
                        .ip("192.168.144.1")
                        .ua("userAgent1"),
                builder -> builder
                        .timeoutMillis(1500L)
                        .tid("tid1"));

        given(uidsCookie.uidFrom(eq(ADAPTER))).willReturn("buyerUid1");

        // when
        final List<AdapterHttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).hasSize(1)
                .extracting(AdapterHttpRequest::getBidRequest)
                .containsOnly(BidRequest.builder()
                        .id("tid1")
                        .at(1)
                        .tmax(1500L)
                        .imp(singletonList(Imp.builder()
                                .id("adUnitCode1")
                                .instl(1)
                                .tagid("adUnitCode1")
                                .banner(Banner.builder()
                                        .w(300)
                                        .h(250)
                                        .topframe(1)
                                        .format(singletonList(Format.builder()
                                                .w(300)
                                                .h(250)
                                                .build()))
                                        .build())
                                .build()))
                        .site(Site.builder()
                                .domain("example.com")
                                .page("http://www.example.com")
                                .publisher(Publisher.builder().id("486").build())
                                .build())
                        .device(Device.builder()
                                .ua("userAgent1")
                                .ip("192.168.144.1")
                                .build())
                        .user(User.builder()
                                .buyeruid("buyerUid1")
                                .build())
                        .source(Source.builder()
                                .fd(1)
                                .tid("tid1")
                                .build())
                        .build());
    }

    @Test
    public void makeHttpRequestsShouldReturnListWithOneRequestIfMultipleAdUnitsInPreBidRequest() {
        // given
        adapterRequest = AdapterRequest.of(ADAPTER, asList(
                givenAdUnitBid(builder -> builder.adUnitCode("adUnitCode1")),
                givenAdUnitBid(builder -> builder.adUnitCode("adUnitCode2"))));

        // when
        final List<AdapterHttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).hasSize(1)
                .flatExtracting(r -> r.getBidRequest().getImp()).hasSize(2)
                .extracting(Imp::getId).containsOnly("adUnitCode1", "adUnitCode2");
    }

    @Test
    public void extractBidsShouldFailIfBidImpIdDoesNotMatchAdUnitCode() {
        // given
        adapterRequest = givenBidder(builder -> builder.adUnitCode("adUnitCode"));

        exchangeCall = givenExchangeCall(identity(),
                bidResponseBuilder -> bidResponseBuilder.seatbid(singletonList(SeatBid.builder()
                        .bid(singletonList(Bid.builder().impid("anotherAdUnitCode").build()))
                        .build())));

        // when and then
        assertThatThrownBy(() -> adapter.extractBids(adapterRequest, exchangeCall))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("Unknown ad unit code 'anotherAdUnitCode'");
    }

    @Test
    public void makeHttpRequestsShouldReturnListWithOneRequestIfAdUnitContainsBannerAndVideoMediaTypes() {
        //given
        adapterRequest = AdapterRequest.of(ADAPTER, singletonList(
                givenAdUnitBid(builder -> builder
                        .mediaTypes(EnumSet.of(MediaType.video, MediaType.banner))
                        .video(Video.builder()
                                .mimes(singletonList("Mime1"))
                                .playbackMethod(1)
                                .build()))));

        preBidRequestContext = givenPreBidRequestContext(identity(), identity());

        // when
        final List<AdapterHttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).hasSize(1)
                .flatExtracting(r -> r.getBidRequest().getImp()).hasSize(2)
                .extracting(imp -> imp.getVideo() == null, imp -> imp.getBanner() == null)
                .containsOnly(tuple(true, false), tuple(false, true));
    }

    @Test
    public void extractBidsShouldReturnMultipleBidBuildersIfMultipleAdUnitsInPreBidRequestAndBidsInResponse() {
        // given
        adapterRequest = AdapterRequest.of(ADAPTER, asList(
                givenAdUnitBid(builder -> builder.adUnitCode("adUnitCode1")),
                givenAdUnitBid(builder -> builder.adUnitCode("adUnitCode2"))));

        exchangeCall = givenExchangeCall(identity(),
                bidResponseBuilder -> bidResponseBuilder.id("bidResponseId")
                        .seatbid(singletonList(SeatBid.builder()
                                .seat("seatId")
                                .bid(asList(Bid.builder().impid("adUnitCode1").build(),
                                        Bid.builder().impid("adUnitCode2").build()))
                                .build())));

        // when
        final List<org.prebid.server.proto.response.Bid> bids =
                adapter.extractBids(adapterRequest, exchangeCall).stream()
                        .map(org.prebid.server.proto.response.Bid.BidBuilder::build).collect(Collectors.toList());

        // then
        assertThat(bids).hasSize(2)
                .extracting(org.prebid.server.proto.response.Bid::getCode)
                .containsOnly("adUnitCode1", "adUnitCode2");
    }

    @Test
    public void extractBidsShouldReturnBidBuildersWithExpectedFields() {
        // given
        adapterRequest = givenBidder(
                builder -> builder.bidderCode(ADAPTER).bidId("bidId").adUnitCode("adUnitCode"));

        exchangeCall = givenExchangeCall(
                bidRequestBuilder -> bidRequestBuilder.imp(singletonList(Imp.builder().id("adUnitCode").build())),
                bidResponseBuilder -> bidResponseBuilder.id("bidResponseId")
                        .seatbid(singletonList(SeatBid.builder()
                                .seat("seatId")
                                .bid(singletonList(Bid.builder()
                                        .impid("adUnitCode")
                                        .price(new BigDecimal("8.43"))
                                        .adm("adm")
                                        .crid("crid")
                                        .w(300)
                                        .h(250)
                                        .dealid("dealId")
                                        .build()))
                                .build())));

        // when
        final List<org.prebid.server.proto.response.Bid> bids =
                adapter.extractBids(adapterRequest, exchangeCall).stream()
                        .map(org.prebid.server.proto.response.Bid.BidBuilder::build).collect(Collectors.toList());

        // then
        assertThat(bids)
                .containsExactly(org.prebid.server.proto.response.Bid.builder()
                        .code("adUnitCode")
                        .price(new BigDecimal("8.43"))
                        .adm("adm")
                        .creativeId("crid")
                        .width(300)
                        .height(250)
                        .bidder(ADAPTER)
                        .bidId("bidId")
                        .dealId("dealId")
                        .build());
    }

    @Test
    public void extractBidsShouldReturnEmptyBidsIfEmptyOrNullBidResponse() {
        // given
        adapterRequest = givenBidder(identity());

        exchangeCall = givenExchangeCall(identity(), br -> br.seatbid(null));

        // when and then
        Assertions.assertThat(adapter.extractBids(adapterRequest, exchangeCall)).isEmpty();
        Assertions.assertThat(adapter.extractBids(adapterRequest, ExchangeCall.empty(null))).isEmpty();
    }

    private static AdapterRequest givenBidder(Function<AdUnitBidBuilder, AdUnitBidBuilder> adUnitBidBuilderCustomizer) {
        return AdapterRequest.of(ADAPTER, singletonList(givenAdUnitBid(adUnitBidBuilderCustomizer)));
    }

    private static AdUnitBid givenAdUnitBid(Function<AdUnitBidBuilder, AdUnitBidBuilder> adUnitBidBuilderCustomizer) {
        final AdUnitBidBuilder adUnitBidBuilderMinimal = AdUnitBid.builder()
                .sizes(singletonList(Format.builder().w(300).h(250).build()))
                .params(mapper.valueToTree(IndexParams.of(42)))
                .mediaTypes(singleton(MediaType.banner));

        final AdUnitBidBuilder adUnitBidBuilderCustomized = adUnitBidBuilderCustomizer
                .apply(adUnitBidBuilderMinimal);

        return adUnitBidBuilderCustomized.build();
    }

    private PreBidRequestContext givenPreBidRequestContext(
            Function<PreBidRequestContextBuilder, PreBidRequestContextBuilder> preBidRequestContextBuilderCustomizer,
            Function<PreBidRequestBuilder, PreBidRequestBuilder> preBidRequestBuilderCustomizer) {

        final PreBidRequestBuilder preBidRequestBuilderMinimal = PreBidRequest.builder()
                .accountId("accountId");
        final PreBidRequest preBidRequest = preBidRequestBuilderCustomizer.apply(preBidRequestBuilderMinimal).build();

        final PreBidRequestContextBuilder preBidRequestContextBuilderMinimal =
                PreBidRequestContext.builder()
                        .preBidRequest(preBidRequest)
                        .uidsCookie(uidsCookie);
        return preBidRequestContextBuilderCustomizer.apply(preBidRequestContextBuilderMinimal).build();
    }

    private static ExchangeCall givenExchangeCall(
            Function<BidRequestBuilder, BidRequestBuilder> bidRequestBuilderCustomizer,
            Function<BidResponseBuilder, BidResponseBuilder> bidResponseBuilderCustomizer) {

        final BidRequestBuilder bidRequestBuilderMinimal = BidRequest.builder();
        final BidRequest bidRequest = bidRequestBuilderCustomizer.apply(bidRequestBuilderMinimal).build();

        final BidResponseBuilder bidResponseBuilderMinimal = BidResponse.builder();
        final BidResponse bidResponse = bidResponseBuilderCustomizer.apply(bidResponseBuilderMinimal).build();

        return ExchangeCall.success(bidRequest, bidResponse, BidderDebug.builder().build());
    }
}
