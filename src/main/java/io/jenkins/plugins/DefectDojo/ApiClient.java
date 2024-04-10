/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.jenkins.plugins.DefectDojo;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.FilePath;
import io.jenkins.plugins.okhttp.api.JenkinsOkHttpClient;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.springframework.http.HttpStatus;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.UniformRandomBackOffPolicy;
import org.springframework.retry.policy.BinaryExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.CompositeRetryPolicy;
import org.springframework.retry.policy.MaxAttemptsRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

public class ApiClient {

    private static final String API_URL = "/api/v2";
    static final String API_KEY_HEADER = "Authorization";
    static final String ENGAGEMENT_URL = API_URL + "/engagements/";
    static final String UPLOAD_URL = API_URL + "/import-scan/";
    static final String REUPLOAD_URL = API_URL + "/reimport-scan/";
    static final String PRODUCT_URL = API_URL + "/products";
    static final String SCAN_TYPE_URL = API_URL + "/test_types";
    static final String TESTS_URL = API_URL + "/tests";
    static final String LOOKUP_TEST_BY_EGAGEMENT_ID_PARAM = "engagement";
    static final String LOOKUP_TEST_PARAM = "scan_type";
    static final String LOOKUP_NAME_PARAM = "name";
    static final String LOOKUP_NAME_EXACT_PARAM = "name_exact";
    static final String LOOKUP_ENGAGEMENT_BY_PRODUCT_ID_PARAM = "product";
    static final String LOOKUP_ID_PARAM = "id";

    /**
     * the base url to DD instance without trailing slashes, e.g.
     * "http://host.tld:port"
     */
    private final String baseUrl;

    /**
     * the api key to authorize with against DT
     */
    private final String apiKey;

    private final ConsoleLogger logger;
    private final OkHttpClient httpClient;

    /**
     *
     * @param baseUrl the base url to DD instance without trailing slashes, e.g.
     * "http://host.tld:port"
     * @param apiKey the api key to authorize with against DT
     * @param logger
     * @param connectionTimeout the connection-timeout in seconds for every call
     * to DT
     * @param readTimeout the read-timeout in seconds for every call to DT
     */
    public ApiClient(@NonNull final String baseUrl, @NonNull final String apiKey, @NonNull final ConsoleLogger logger, final int connectionTimeout, final int readTimeout) {
        this(baseUrl, apiKey, logger, () -> JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                .connectTimeout(Duration.ofSeconds(connectionTimeout))
                .readTimeout(Duration.ofSeconds(readTimeout))
                .build());
    }

    ApiClient(@NonNull final String baseUrl, @NonNull final String apiKey, @NonNull final ConsoleLogger logger, @NonNull final HttpClientFactory factory) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.logger = logger;
        httpClient = factory.create();
    }

    @NonNull
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    @SuppressWarnings("lgtm[jenkins/credentials-fill-without-permission-check]")
    public boolean testConnection() throws ApiClientException {
        final var request = createRequest(URI.create(PRODUCT_URL));
        return executeWithRetry(() -> {
            try (var response = httpClient.newCall(request).execute()) {
                logger.log(response.toString());
                if (response.isSuccessful()) {
                    return true;
                } else {
                    final int status = response.code();
                    logger.log(response.body().string());
                    throw new ApiClientException(Messages.ApiClient_Error_Connection(status, HttpStatus.valueOf(status).getReasonPhrase()));
                }
            } catch (ApiClientException e) {
                throw e;
            } catch (IOException e) {
                throw new ApiClientException(Messages.ApiClient_Error_Connection(StringUtils.EMPTY, StringUtils.EMPTY), e);
            }
        });
    }

    @NonNull
    public List<JSONObject> getProducts() throws ApiClientException {
        return getData(PRODUCT_URL);
    }

    @NonNull
    public List<JSONObject> getEngagements(final int productID) throws ApiClientException {
        String URL = ENGAGEMENT_URL + "?product=" + String.valueOf(productID);
        return getData(URL);
    }

    @NonNull
    public List<JSONObject> getScanTypes() throws ApiClientException {
        return getData(SCAN_TYPE_URL);
    }

    @NonNull
    public String getProductId(final String productName) throws ApiClientException {
        final var uri = UriComponentsBuilder.fromUriString(PRODUCT_URL)
                .queryParam(LOOKUP_NAME_EXACT_PARAM, "{productName}")
                .build(productName);
        return getIdFromDojo(createRequest(uri));
    }

    @NonNull
    public String getEngagementId(@Nullable final String productId, final String engagementName) throws ApiClientException {
        final var uriBuilder = UriComponentsBuilder.fromUriString(ENGAGEMENT_URL)
                .queryParam(LOOKUP_NAME_PARAM, "{engagementName}");
        var uri = uriBuilder.build(engagementName);
        if (productId != null) {
            uriBuilder.queryParam(LOOKUP_ENGAGEMENT_BY_PRODUCT_ID_PARAM, "{productId}");
            uri = uriBuilder.build(engagementName, productId);
        }
        return getIdFromDojo(createRequest(uri));
    }

    @NonNull
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    @SuppressWarnings({"deprecation","lgtm[jenkins/credentials-fill-without-permission-check]"})
    public Boolean upload(final String projectId, final String engagementId, @Nullable final String sourceCodeUri, @Nullable String branchTag, @Nullable String commitHash,
            @NonNull final FilePath artifact, @NonNull final String scanType, boolean reuploadScan) throws IOException, InterruptedException {
        if (!artifact.exists()) {
            logger.log(Messages.Builder_Error_Processing(artifact.getRemote(), "e.getLocalizedMessage()"));
            return false;
        }
        String scanId = null;
        String url = UPLOAD_URL;
        JSONObject jsonBody = new JSONObject();

        jsonBody.put("scan_type", scanType);
        jsonBody.put("engagement", engagementId);
        jsonBody.put("product_id", projectId);
        
        if (StringUtils.isNotBlank(sourceCodeUri)) {
            jsonBody.put("source_code_management_uri", sourceCodeUri);
        }
        if (StringUtils.isNotBlank(branchTag)){
            jsonBody.put("branch_tag", branchTag);
        }
        if (StringUtils.isNotBlank(commitHash)) {
            jsonBody.put("commit_hash", commitHash);
        }
        jsonBody.put("do_not_reactivate", "true");
        jsonBody.put("active ", "false");
        jsonBody.put("verified", "false");
        jsonBody.put("environment", "");
        jsonBody.put("minimum_severity", "Low");

        RequestBody fileRequestBody = RequestBody.create(okhttp3.MediaType.parse("application/octet-stream"), new File(artifact.getRemote()));
        
        if (StringUtils.isNotBlank(engagementId)) {
            scanId = getScanId(engagementId, scanType);
        }

        if (reuploadScan && StringUtils.isNotBlank(scanId)) {
            url = REUPLOAD_URL;

            jsonBody.put("test", scanId);
            jsonBody.remove("active ");
            jsonBody.remove("verified");
        }
        
        RequestBody uploadBody = createMultipartBody(jsonBody, fileRequestBody);
        final var request = createRequest(URI.create(url), "POST", uploadBody);
        return executeWithRetry(() -> {
            try (var response = httpClient.newCall(request).execute()) {
                final var body = response.body().string();
                final int status = response.code();
                // Checks the server response
                switch (status) {
                    case HTTP_OK:
                    case HTTP_ACCEPTED:
                    case HTTP_CREATED:
                        return true;
                    case HTTP_BAD_REQUEST:
                        logger.log(Messages.Builder_Payload_Invalid());
                        break;
                    case HTTP_UNAUTHORIZED:
                        logger.log(Messages.Builder_Unauthorized());
                        break;
                    case HTTP_NOT_FOUND:
                        logger.log(Messages.Builder_Product_NotFound());
                        break;
                    default:
                        logger.log(Messages.ApiClient_Error_Connection(status, HttpStatus.valueOf(status).getReasonPhrase()));
                        break;
                }
                logger.log(body);
                return false;
            }
        });

    }

    @RequirePOST
    public String createEngagement(String engagementName, String productId, @Nullable String sourceCodeUrl) throws IOException {
        final String defaultValues = "{\"description\": \"Auto-created via Jenkins\",\"engagement_type\":\"Interactive\",\"status\": \"In Progress\",\"deduplication_on_engagement\": \"true\"}";
        JSONObject jsonBody = JSONObject.fromObject(defaultValues);
        LocalDate currentDate = LocalDate.now();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        jsonBody.put("name", engagementName);
        jsonBody.put("product", productId);
        jsonBody.put("target_start", currentDate.format(dateFormatter));
        jsonBody.put("target_end", currentDate.plusDays(30).format(dateFormatter));

        if (StringUtils.isNotBlank(sourceCodeUrl)) {
            jsonBody.put("source_code_management_uri", sourceCodeUrl);
        }
        
        logger.log(jsonBody.toString());

        final var request = createRequest(URI.create(ENGAGEMENT_URL), "POST", RequestBody.create(jsonBody.toString(), okhttp3.MediaType.parse(APPLICATION_JSON_VALUE)));
        return executeWithRetry(() -> {
            try (var response = httpClient.newCall(request).execute()) {
                final var body = response.body().string();
                final int status = response.code();
                // Checks the server response
                switch (status) {
                    case HTTP_CREATED:
                        return (JSONObject.fromObject(body).get("id")).toString();
                    case HTTP_BAD_REQUEST:
                        logger.log(Messages.Builder_Payload_Invalid());
                        break;
                    case HTTP_UNAUTHORIZED:
                        logger.log(Messages.Builder_Unauthorized());
                        break;
                    default:
                        logger.log(Messages.ApiClient_Error_Connection(status, HttpStatus.valueOf(status).getReasonPhrase()));
                        break;
                }
                logger.log(body);
                return null;
            }
        });
    }

    @NonNull
    private List<JSONObject> getData(final String URL) throws ApiClientException {
        List<JSONObject> data = new ArrayList<>();
        int offset = 0;
        boolean fetchMore = true;
        while (fetchMore) {
            final JSONArray pagedData = (JSONArray) getPaged(offset, offset+=500, URL);
            List<JSONObject> fetchedData = pagedData.stream()
                    .map(JSONObject.class::cast)
                    .collect(Collectors.toList());
            fetchMore = !fetchedData.isEmpty();
            data.addAll(fetchedData);
        }
        return data;
    }

    @NonNull
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    @SuppressWarnings("lgtm[jenkins/credentials-fill-without-permission-check]")
    private List<Object> getPaged(final int offset, final int limit, final String URL) throws ApiClientException {
        final var uri = UriComponentsBuilder.fromUriString(URL)
                .queryParam("limit", "{limit}")
                .queryParam("offset", "{offset}")
                .build(limit, offset);
        final var request = createRequest(uri);
        return executeWithRetry(() -> {
            try (var response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return getRequestResult(response.body().string());
                }
                return List.of();
            } catch (IOException e) {
                throw new ApiClientException(Messages.ApiClient_Error_Connection(StringUtils.EMPTY, StringUtils.EMPTY), e);
            }
        });
    }

    @NonNull
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private String getScanId(@NonNull final String engagmentId, @NonNull final String scanType) throws ApiClientException {
        final var uri = UriComponentsBuilder.fromUriString(TESTS_URL)
                .queryParam(LOOKUP_TEST_BY_EGAGEMENT_ID_PARAM, "{id}")
                .queryParam(LOOKUP_TEST_PARAM, "{scanType}")
                .build(engagmentId, scanType);
        return getIdFromDojo(createRequest(uri));
    }

    @SuppressWarnings("lgtm[jenkins/credentials-fill-without-permission-check]")
    private String getIdFromDojo(final Request request) throws ApiClientException {
        return executeWithRetry(() -> {
            try (var response = httpClient.newCall(request).execute()) {
                final var body = response.body().string();
                if (!response.isSuccessful()) {
                    logger.log(body);
                }
                final var results = getRequestResult(body);
                if(results.size() > 0) {
                    return ((JSONObject) results.get(0)).getString("id");
                }
                return null;
            } catch (ApiClientException e) {
                throw e;
            } catch (IOException e) {
                throw new ApiClientException(Messages.ApiClient_Error_Connection(StringUtils.EMPTY, StringUtils.EMPTY), e);
            }
        });
    }

    private JSONArray getRequestResult(final String response) {
        return JSONObject.fromObject(response).getJSONArray("results");
    }

    @SuppressWarnings("unchecked")
    private RequestBody createMultipartBody(JSONObject json, @Nullable RequestBody filePart) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);

        // Convert JSON string to form parts
        json.keySet().forEach(key -> {
            Object value = json.get(key);
            builder.addFormDataPart(key.toString(), value.toString());
        });

        if (filePart != null) {
            builder.addFormDataPart("file", "file.json", filePart);
        }

        return builder.build();
    }

    private Request createRequest(final URI uri) {
        return createRequest(uri, "GET", null);
    }

    private Request createRequest(final URI uri, final String method, final RequestBody bodyPublisher) {
        return new Request.Builder()
                .url(baseUrl + uri)
                .addHeader(API_KEY_HEADER, "Token " + apiKey)
                .addHeader(ACCEPT, APPLICATION_JSON_VALUE)
                .method(method, bodyPublisher)
                .build();
    }

    private <T, E extends IOException> T executeWithRetry(RetryAction<T, E> action) throws E {
        final var exceptionClassifier = new ApiClientExceptionClassifier();
        final var retryPolicy = new CompositeRetryPolicy();
        final var backOffPolicy = new UniformRandomBackOffPolicy();
        final var template = new RetryTemplate();

        backOffPolicy.setMinBackOffPeriod(50);
        backOffPolicy.setMaxBackOffPeriod(500);
        retryPolicy.setPolicies(new RetryPolicy[]{new MaxAttemptsRetryPolicy(2), new BinaryExceptionClassifierRetryPolicy(exceptionClassifier)});
        template.setBackOffPolicy(backOffPolicy);
        template.setRetryPolicy(retryPolicy);

        return template.execute(ctx -> action.doWithRetry());
    }

    private interface RetryAction<T, E extends IOException> {

        T doWithRetry() throws E;
    }
}
