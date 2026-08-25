package rs.sud.eaukcija.client;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import rs.sud.eaukcija.client.EAukcijaApiTypes.ApiResponse;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionDetail;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionListData;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryNode;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryRequest;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryTree;
import rs.sud.eaukcija.client.EAukcijaApiTypes.DetailRequest;
import rs.sud.eaukcija.client.EAukcijaApiTypes.RejectedAuctionSummary;

/** Bounded, rate-limited client for the undocumented eAukcija SPA backend. */
@Component
public final class EAukcijaClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String ORIGINAL_RETRY_AFTER = "X-Aukcije-Original-Retry-After";
    private static final int DEFAULT_TEXT_COLUMN_LENGTH = 255;
    private static final int SHORT_DESCRIPTION_COLUMN_LENGTH = 2_000;
    private static final int DESCRIPTION_COLUMN_LENGTH = 4_000;
    private static final int MONEY_INTEGER_DIGITS = 36;
    private static final int MONEY_SCALE = 2;
    private static final Set<Integer> RETRYABLE_HTTP_STATUSES = Set.of(
            408, 429, 500, 502, 503, 504);

    private final EAukcijaClientProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final HttpUrl baseUrl;
    private final EAukcijaTiming timing;
    private final EAukcijaJitter jitter;
    private final EAukcijaRateGate rateGate;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    @Autowired
    public EAukcijaClient(
            EAukcijaClientProperties properties,
            ObjectMapper objectMapper,
            @Value("${eaukcija.api.allow-http-loopback-test:false}") boolean allowHttpForLoopbackTest) {
        this(properties, objectMapper, EAukcijaTiming.system(),
                inclusiveMaximum -> inclusiveMaximum == 0
                        ? 0
                        : ThreadLocalRandom.current().nextLong(inclusiveMaximum + 1),
                allowHttpForLoopbackTest,
                List.of());
    }

    public EAukcijaClient(EAukcijaClientProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, false);
    }

    EAukcijaClient(
            EAukcijaClientProperties properties,
            ObjectMapper objectMapper,
            EAukcijaTiming timing,
            EAukcijaJitter jitter,
            boolean allowHttpForLoopbackTest) {
        this(properties, objectMapper, timing, jitter, allowHttpForLoopbackTest, List.of());
    }

    EAukcijaClient(
            EAukcijaClientProperties properties,
            ObjectMapper objectMapper,
            EAukcijaTiming timing,
            EAukcijaJitter jitter,
            boolean allowHttpForLoopbackTest,
            List<Interceptor> interceptors) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.timing = Objects.requireNonNull(timing, "timing");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        properties.validate(allowHttpForLoopbackTest);
        this.baseUrl = HttpUrl.get(properties.getBaseUrl());
        OkHttpClient.Builder transport = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(properties.getReadTimeout())
                .callTimeout(properties.getCallTimeout())
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false);
        Objects.requireNonNull(interceptors, "interceptors").forEach(
                interceptor -> transport.addInterceptor(
                        Objects.requireNonNull(interceptor, "interceptor")));
        // OkHttp 4.12 parses numeric Retry-After values as an Integer while
        // deciding whether to follow up 408/503 responses. Preserve oversized
        // source values under a private response-only header and present a safe,
        // terminal value to that internal parser. Our policy layer below still
        // observes and honors the original duration.
        transport.addNetworkInterceptor(chain -> preserveOversizedRetryAfter(
                chain.proceed(chain.request())));
        this.httpClient = transport.build();
        this.rateGate = new EAukcijaRateGate(
                properties.getRequestsPerSecond(), properties.getMaxConcurrency(), timing);
    }

    public EAukcijaCallResult<CategoryTree> getCategories() {
        JavaType categoryNode = objectMapper.getTypeFactory().constructType(CategoryNode.class);
        JavaType categoryList = objectMapper.getTypeFactory().constructCollectionType(List.class, categoryNode);
        return post(Endpoint.CATEGORIES, Map.of(), categoryList, this::validatedCategoryTree);
    }

    public EAukcijaCallResult<AuctionListData> getAuctionsByCategory(
            int categoryId, int pageSize, int page) {
        if (categoryId < 1 || pageSize < 1 || pageSize > 3_000 || page < 1) {
            throw new IllegalArgumentException(
                    "categoryId and page must be positive and pageSize must be between 1 and 3000");
        }
        return post(
                Endpoint.AUCTIONS_BY_CATEGORY,
                new CategoryRequest(categoryId, pageSize, page),
                objectMapper.getTypeFactory().constructType(AuctionListData.class),
                this::validatedAuctionPage);
    }

    public EAukcijaCallResult<AuctionDetail> getImmovablePropertyDetails(long auctionId) {
        return getPropertyDetails(Endpoint.IMMOVABLE_PROPERTY_DETAIL, auctionId);
    }

    public EAukcijaCallResult<AuctionDetail> getCommonPropertyDetails(long auctionId) {
        return getPropertyDetails(Endpoint.COMMON_PROPERTY_DETAIL, auctionId);
    }

    private EAukcijaCallResult<AuctionDetail> getPropertyDetails(
            Endpoint endpoint, long auctionId) {
        if (auctionId < 1) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        return post(
                endpoint,
                new DetailRequest(auctionId),
                objectMapper.getTypeFactory().constructType(AuctionDetail.class),
                (AuctionDetail detail) -> validatedDetail(detail, auctionId));
    }

    OkHttpClient transportForTesting() {
        return httpClient;
    }

    /** Cancels the physical call promptly before the managed worker is stopped. */
    @EventListener(ContextClosedEvent.class)
    void prepareForShutdown() {
        shuttingDown.set(true);
        httpClient.dispatcher().cancelAll();
    }

    private <T, R> EAukcijaCallResult<R> post(
            Endpoint endpoint,
            Object requestValue,
            JavaType payloadType,
            PayloadValidator<T, R> validator) {
        Request request = request(endpoint, requestValue);
        JavaType envelopeType = objectMapper.getTypeFactory()
                .constructParametricType(ApiResponse.class, payloadType);

        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            if (shuttingDown.get()) {
                throw failure(EAukcijaErrorCode.INTERRUPTED, endpoint, null, attempt);
            }
            Duration retryDelay = null;
            try (EAukcijaRateGate.Permit ignored = acquire(endpoint, attempt);
                    Response response = httpClient.newCall(request).execute()) {
                int status = response.code();
                if (!response.isSuccessful()) {
                    Duration retryAfter = retryAfter(response);
                    if (retryAfter != null) {
                        if (retryAfter.compareTo(properties.getMaxRetryAfter()) > 0) {
                            // Refusing this run's retry must not allow the next caller to
                            // ignore the source's longer, shared cool-down instruction.
                            rateGate.pause(retryAfter);
                            throw failure(EAukcijaErrorCode.RATE_LIMITED, endpoint, status, attempt);
                        }
                    }
                    if (RETRYABLE_HTTP_STATUSES.contains(status)
                            && attempt < properties.getMaxAttempts()) {
                        retryDelay = maximum(backoff(attempt), retryAfter);
                        if (retryAfter != null) {
                            // The next acquire performs this wait once for the retrying caller,
                            // while the shared gate holds peers until the same deadline.
                            rateGate.pause(retryDelay);
                            retryDelay = Duration.ZERO;
                        }
                    } else {
                        // A terminal response can still ask the shared client to delay peers.
                        rateGate.pause(retryAfter);
                        EAukcijaErrorCode code = status == 429
                                ? EAukcijaErrorCode.RATE_LIMITED
                                : EAukcijaErrorCode.HTTP_STATUS;
                        throw failure(code, endpoint, status, attempt);
                    }
                } else {
                    ParsedEnvelope<T> parsed = parse(response, endpoint, attempt, envelopeType);
                    if (!"0".equals(parsed.envelope().resultCode())) {
                        throw failure(EAukcijaErrorCode.APPLICATION_ERROR, endpoint, status, attempt, parsed);
                    }
                    if (parsed.envelope().data() == null) {
                        throw failure(EAukcijaErrorCode.INVALID_ENVELOPE, endpoint, status, attempt, parsed);
                    }
                    try {
                        R validated = validator.validate(parsed.envelope().data());
                        return EAukcijaCallResult.success(validated, attempt);
                    } catch (ValidationFailure invalid) {
                        throw failure(invalid.code, endpoint, status, attempt, parsed);
                    }
                }
            } catch (EAukcijaClientException expected) {
                throw expected;
            } catch (SourceReadException readFailure) {
                if (shuttingDown.get() || Thread.currentThread().isInterrupted()) {
                    throw failure(EAukcijaErrorCode.INTERRUPTED, endpoint, null, attempt);
                }
                if (attempt >= properties.getMaxAttempts()) {
                    EAukcijaErrorCode code = readFailure.timeout
                            ? EAukcijaErrorCode.TIMEOUT
                            : EAukcijaErrorCode.IO;
                    throw failure(code, endpoint, null, attempt);
                }
                retryDelay = backoff(attempt);
            } catch (IOException transportFailure) {
                if (shuttingDown.get() || Thread.currentThread().isInterrupted()) {
                    throw failure(EAukcijaErrorCode.INTERRUPTED, endpoint, null, attempt);
                }
                if (attempt >= properties.getMaxAttempts()) {
                    EAukcijaErrorCode code = isTimeout(transportFailure)
                            ? EAukcijaErrorCode.TIMEOUT
                            : EAukcijaErrorCode.IO;
                    throw failure(code, endpoint, null, attempt);
                }
                retryDelay = backoff(attempt);
            }

            sleepBeforeRetry(retryDelay, endpoint, attempt);
        }
        throw failure(EAukcijaErrorCode.INTERNAL, endpoint, null, properties.getMaxAttempts());
    }

    private Request request(Endpoint endpoint, Object requestValue) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(requestValue);
        } catch (JsonProcessingException impossibleForRequestRecords) {
            throw failure(EAukcijaErrorCode.INTERNAL, endpoint, null, 1);
        }
        HttpUrl url = baseUrl.newBuilder().addPathSegment(endpoint.path).build();
        return new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", properties.requestUserAgent())
                .post(RequestBody.create(json, JSON))
                .build();
    }

    private EAukcijaRateGate.Permit acquire(Endpoint endpoint, int attempt) {
        try {
            return rateGate.acquire(properties.getMaxRetryAfter());
        } catch (EAukcijaRateGate.PauseBeyondBudgetException paused) {
            throw failure(EAukcijaErrorCode.RATE_LIMITED, endpoint, null, attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(EAukcijaErrorCode.INTERRUPTED, endpoint, null, attempt);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ParsedEnvelope<T> parse(
            Response response, Endpoint endpoint, int attempt, JavaType envelopeType)
            throws SourceReadException {
        ResponseBody body = response.body();
        if (body == null || body.contentLength() == 0) {
            throw failure(EAukcijaErrorCode.INVALID_ENVELOPE, endpoint, response.code(), attempt);
        }
        MediaType contentType = body.contentType();
        if (!isJson(contentType)) {
            throw failure(EAukcijaErrorCode.INVALID_CONTENT_TYPE, endpoint, response.code(), attempt);
        }
        long declaredLength = body.contentLength();
        if (declaredLength > properties.getMaxResponseBytes()) {
            throw new EAukcijaClientException(
                    EAukcijaErrorCode.BODY_TOO_LARGE,
                    endpoint.label,
                    response.code(),
                    attempt,
                    declaredLength,
                    null);
        }

        MessageDigest digest = sha256();
        BoundedInputStream bounded = new BoundedInputStream(
                body.byteStream(), properties.getMaxResponseBytes(), digest);
        try (JsonParser parser = objectMapper.getFactory().createParser(bounded)) {
            ApiResponse<T> envelope = (ApiResponse<T>) objectMapper.readValue(parser, envelopeType);
            if (parser.nextToken() != null) {
                throw new ValidationFailure(EAukcijaErrorCode.INVALID_JSON);
            }
            return new ParsedEnvelope<>(
                    envelope,
                    bounded.count(),
                    HexFormat.of().formatHex(digest.digest()));
        } catch (ValidationFailure trailingJson) {
            throw failure(
                    trailingJson.code,
                    endpoint,
                    response.code(),
                    attempt,
                    bounded.count(),
                    null);
        } catch (IOException parsingFailure) {
            if (hasCause(parsingFailure, BodyLimitExceededException.class)) {
                throw failure(
                        EAukcijaErrorCode.BODY_TOO_LARGE,
                        endpoint,
                        response.code(),
                        attempt,
                        bounded.count(),
                        null);
            }
            if (isTimeout(parsingFailure)) {
                throw new SourceReadException(true);
            }
            if (hasCause(parsingFailure, ProtocolException.class)
                    || hasCause(parsingFailure, SocketException.class)) {
                throw new SourceReadException(false);
            }
            if (parsingFailure instanceof JsonProcessingException) {
                throw failure(
                        EAukcijaErrorCode.INVALID_JSON,
                        endpoint,
                        response.code(),
                        attempt,
                        bounded.count(),
                        null);
            }
            throw new SourceReadException(false);
        }
    }

    private CategoryTree validatedCategoryTree(List<CategoryNode> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
        Set<Integer> seen = new HashSet<>();
        List<CategoryNode> canonicalRoots = canonicalCategories(roots, seen, 0);
        Set<Integer> rootIds = new HashSet<>();
        canonicalRoots.forEach(root -> rootIds.add(root.value()));
        if (!rootIds.containsAll(properties.getRootCategoryIds())) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
        String canonicalJson = categoryJson(canonicalRoots);
        return new CategoryTree(canonicalRoots, canonicalJson, sha256(canonicalJson));
    }

    private List<CategoryNode> canonicalCategories(
            List<CategoryNode> categories, Set<Integer> seen, int depth) {
        if (categories == null || depth > 16 || seen.size() > 10_000) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
        List<CategoryNode> canonical = new ArrayList<>(categories.size());
        for (CategoryNode category : categories) {
            if (category == null
                    || category.value() < 1
                    || !seen.add(category.value())
                    || category.title() == null
                    || category.title().isBlank()
                    || category.categoryType() == null
                    || category.categoryType().isBlank()
                    || !Objects.equals(category.key(), "category" + category.value())
                    || category.children() == null) {
                throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
            }
            canonical.add(new CategoryNode(
                    category.title(),
                    category.value(),
                    category.key(),
                    canonicalCategories(category.children(), seen, depth + 1),
                    category.categoryType()));
        }
        canonical.sort(Comparator.comparingInt(CategoryNode::value));
        return List.copyOf(canonical);
    }

    private String categoryJson(List<CategoryNode> roots) {
        StringWriter output = new StringWriter();
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(output)) {
            generator.writeStartArray();
            for (CategoryNode root : roots) {
                writeCategory(generator, root);
            }
            generator.writeEndArray();
        } catch (IOException impossibleForStringWriter) {
            throw new ValidationFailure(EAukcijaErrorCode.INTERNAL);
        }
        return output.toString();
    }

    private static void writeCategory(JsonGenerator generator, CategoryNode category) throws IOException {
        generator.writeStartObject();
        generator.writeNumberField("value", category.value());
        generator.writeStringField("key", category.key());
        generator.writeStringField("title", category.title());
        generator.writeStringField("categoryType", category.categoryType());
        generator.writeArrayFieldStart("children");
        for (CategoryNode child : category.children()) {
            writeCategory(generator, child);
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    private static String sha256(String value) {
        MessageDigest digest = sha256();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private AuctionListData validatedAuctionPage(AuctionListData data) {
        if (data.totalCount() == null || data.totalCount() < 0 || data.auctions() == null) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
        List<AuctionSummary> validAuctions = new ArrayList<>(data.auctions().size());
        List<RejectedAuctionSummary> rejectedAuctions = new ArrayList<>();
        for (AuctionSummary auction : data.auctions()) {
            if (auction == null || auction.id() < 1) {
                throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
            }
            try {
                validateAuctionSummary(auction);
                validAuctions.add(auction);
            } catch (ValidationFailure invalidRow) {
                rejectedAuctions.add(new RejectedAuctionSummary(
                        auction.id(),
                        AuctionSummaryFingerprint.sha256(auction),
                        EAukcijaErrorCode.INVALID_DATA));
            }
        }
        return new AuctionListData(List.copyOf(validAuctions), data.totalCount(), rejectedAuctions);
    }

    private static void validateAuctionSummary(AuctionSummary auction) {
        if (auction.auctionNumber() == null || auction.auctionNumber().isBlank()) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
        requirePersistableText(auction.auctionNumber(), DEFAULT_TEXT_COLUMN_LENGTH);
        requireInstant(auction.startDate());
        requireInstant(auction.endDate());
        requirePersistableMoney(auction.startingPrice());
        requirePersistableMoney(auction.currentPrice());
        requirePersistableMoney(auction.maxOfferedPrice());
        requirePersistableText(auction.shortDescription(), SHORT_DESCRIPTION_COLUMN_LENGTH);
        requirePersistableText(auction.status(), DEFAULT_TEXT_COLUMN_LENGTH);
        requirePersistableText(auction.propertyType(), DEFAULT_TEXT_COLUMN_LENGTH);
    }

    private AuctionDetail validatedDetail(AuctionDetail detail, long requestedId) {
        if (detail.id() < 1
                || detail.id() != requestedId
                || detail.auctionNumber() == null
                || detail.auctionNumber().isBlank()) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
        requirePersistableText(detail.auctionNumber(), DEFAULT_TEXT_COLUMN_LENGTH);
        requireInstant(detail.startDate());
        requireInstant(detail.endDate());
        if (detail.publicationDate() != null && !detail.publicationDate().isBlank()) {
            requireInstant(detail.publicationDate());
        }
        requirePersistableMoney(detail.startingPrice());
        requirePersistableMoney(detail.estimatedPrice());
        requirePersistableMoney(detail.currentPrice());
        requirePersistableMoney(detail.maxOfferedPrice());
        requirePersistableMoney(detail.bidStep());
        requirePersistableText(detail.shortDescription(), SHORT_DESCRIPTION_COLUMN_LENGTH);
        requirePersistableText(detail.description(), DESCRIPTION_COLUMN_LENGTH);
        requirePersistableText(detail.status(), DEFAULT_TEXT_COLUMN_LENGTH);
        requirePersistableText(detail.propertyType(), DEFAULT_TEXT_COLUMN_LENGTH);
        requirePersistableText(detail.executorName(), DEFAULT_TEXT_COLUMN_LENGTH);
        if (detail.category() != null
                && (detail.category().id() < 1
                || detail.category().name() == null
                || detail.category().name().isBlank())) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
        if (detail.category() != null) {
            requirePersistableText(detail.category().name(), DEFAULT_TEXT_COLUMN_LENGTH);
        }
        if (detail.place() != null) {
            requirePersistableText(detail.place().name(), DEFAULT_TEXT_COLUMN_LENGTH);
            requirePersistableText(detail.place().zipCode(), DEFAULT_TEXT_COLUMN_LENGTH);
            requirePersistableText(detail.place().municipality(), DEFAULT_TEXT_COLUMN_LENGTH);
            requirePersistableText(detail.place().cadastral(), DEFAULT_TEXT_COLUMN_LENGTH);
        }
        return detail;
    }

    /**
     * Mirrors PostgreSQL/JPA VARCHAR bounds before a source value can enter a
     * bulk promotion. PostgreSQL text values cannot contain U+0000; rejecting it
     * here turns a record-specific database failure into redacted INVALID_DATA.
     */
    private static void requirePersistableText(String value, int maximumCharacters) {
        if (value == null) {
            return;
        }
        if (value.indexOf('\0') >= 0
                || value.codePointCount(0, value.length()) > maximumCharacters) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
    }

    /**
     * Requires an exact value in the auctions NUMERIC(38,2) columns. Trailing
     * zeroes are harmless, but a non-zero third decimal or more than 36 integer
     * digits would otherwise be rounded or rejected only during promotion.
     */
    private static void requirePersistableMoney(BigDecimal value) {
        if (value == null) {
            return;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        long integerDigits = Math.max(
                0L,
                (long) normalized.precision() - normalized.scale());
        if (normalized.scale() > MONEY_SCALE || integerDigits > MONEY_INTEGER_DIGITS) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
    }

    private static void requireInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
        try {
            Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new ValidationFailure(EAukcijaErrorCode.INVALID_DATA);
        }
    }

    private Duration retryAfter(Response response) {
        String value = response.header(ORIGINAL_RETRY_AFTER);
        if (value == null) {
            value = response.header("Retry-After");
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        Duration deltaSeconds = retryAfterDeltaSeconds(trimmed);
        if (deltaSeconds != null) {
            return deltaSeconds;
        }
        try {
            Instant retryAt = ZonedDateTime.parse(
                    trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(timing.now(), retryAt);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException invalidDate) {
            return null;
        }
    }

    private static Response preserveOversizedRetryAfter(Response response) {
        String value = response.header("Retry-After");
        Response.Builder sanitized = response.newBuilder().removeHeader(ORIGINAL_RETRY_AFTER);
        if (value != null && exceedsIntegerDeltaSeconds(value.trim())) {
            sanitized.header(ORIGINAL_RETRY_AFTER, value);
            sanitized.header("Retry-After", Integer.toString(Integer.MAX_VALUE));
        }
        return sanitized.build();
    }

    private static boolean exceedsIntegerDeltaSeconds(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int seconds = 0;
        for (int index = 0; index < value.length(); index++) {
            int digit = value.charAt(index) - '0';
            if (digit < 0 || digit > 9) {
                return false;
            }
            if (seconds > (Integer.MAX_VALUE - digit) / 10) {
                return true;
            }
            seconds = seconds * 10 + digit;
        }
        return false;
    }

    private static Duration retryAfterDeltaSeconds(String value) {
        if (value.isEmpty()) {
            return null;
        }
        long seconds = 0;
        for (int index = 0; index < value.length(); index++) {
            int digit = value.charAt(index) - '0';
            if (digit < 0 || digit > 9) {
                return null;
            }
            if (seconds > (Long.MAX_VALUE - digit) / 10) {
                return Duration.ofSeconds(Long.MAX_VALUE);
            }
            seconds = seconds * 10 + digit;
        }
        return Duration.ofSeconds(seconds);
    }

    private Duration backoff(int failedAttempt) {
        long base = properties.getRetryBaseDelay().toNanos();
        long maximum = properties.getRetryMaxDelay().toNanos();
        long multiplier = 1L << Math.min(30, Math.max(0, failedAttempt - 1));
        long cap = base > maximum / multiplier ? maximum : Math.min(maximum, base * multiplier);
        return Duration.ofNanos(jitter.choose(cap));
    }

    private void sleepBeforeRetry(Duration delay, Endpoint endpoint, int attempts) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            timing.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(EAukcijaErrorCode.INTERRUPTED, endpoint, null, attempts);
        }
    }

    private static Duration maximum(Duration left, Duration right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static boolean isJson(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.type())
                && ("json".equalsIgnoreCase(mediaType.subtype())
                        || mediaType.subtype().toLowerCase().endsWith("+json"));
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof InterruptedIOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static EAukcijaClientException failure(
            EAukcijaErrorCode code, Endpoint endpoint, Integer status, int attempts) {
        return new EAukcijaClientException(code, endpoint.label, status, attempts, null, null);
    }

    private static EAukcijaClientException failure(
            EAukcijaErrorCode code,
            Endpoint endpoint,
            Integer status,
            int attempts,
            ParsedEnvelope<?> parsed) {
        return failure(code, endpoint, status, attempts, parsed.bodyBytes(), parsed.bodySha256());
    }

    private static EAukcijaClientException failure(
            EAukcijaErrorCode code,
            Endpoint endpoint,
            Integer status,
            int attempts,
            Long bodyBytes,
            String bodySha256) {
        return new EAukcijaClientException(
                code, endpoint.label, status, attempts, bodyBytes, bodySha256);
    }

    private enum Endpoint {
        CATEGORIES("categories", "GetCategories"),
        AUCTIONS_BY_CATEGORY("auctions-by-category", "GetAuctionsByCategoryId"),
        IMMOVABLE_PROPERTY_DETAIL("immovable-property-detail", "GetImmovablePropertyDetails"),
        COMMON_PROPERTY_DETAIL("common-property-detail", "GetCommonPropertyDetails");

        private final String label;
        private final String path;

        Endpoint(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    @FunctionalInterface
    interface EAukcijaJitter {
        long choose(long inclusiveMaximumNanos);
    }

    @FunctionalInterface
    private interface PayloadValidator<T, R> {
        R validate(T payload);
    }

    private record ParsedEnvelope<T>(ApiResponse<T> envelope, long bodyBytes, String bodySha256) {
    }

    private static final class ValidationFailure extends RuntimeException {
        private final EAukcijaErrorCode code;

        private ValidationFailure(EAukcijaErrorCode code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    private static final class SourceReadException extends IOException {
        private final boolean timeout;

        private SourceReadException(boolean timeout) {
            super();
            this.timeout = timeout;
        }
    }

    private static final class BodyLimitExceededException extends IOException {
        private BodyLimitExceededException() {
            super();
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximumBytes;
        private final MessageDigest digest;
        private long count;

        private BoundedInputStream(InputStream delegate, long maximumBytes, MessageDigest digest) {
            super(delegate);
            this.maximumBytes = maximumBytes;
            this.digest = digest;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                accept(new byte[] {(byte) value}, 0, 1);
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long remainingPlusProbe = maximumBytes - count + 1;
            int boundedLength = (int) Math.min(length, Math.max(1L, remainingPlusProbe));
            int read = super.read(target, offset, boundedLength);
            if (read > 0) {
                accept(target, offset, read);
            }
            return read;
        }

        long count() {
            return count;
        }

        private void accept(byte[] bytes, int offset, int length) throws IOException {
            count += length;
            if (count > maximumBytes) {
                throw new BodyLimitExceededException();
            }
            digest.update(bytes, offset, length);
        }
    }
}
