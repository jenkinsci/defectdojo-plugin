/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.jenkins.plugins.DefectDojo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.FilePath;
import hudson.util.Secret;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;

/**
 *
 * @author Ronny "Sephiroth" Perinke <sephiroth@sephiroth-j.de>
 */
@ExtendWith(MockitoExtension.class)
@WithJenkins
class ApiClientTest {

    private static final Secret API_KEY = Secret.fromString("api-key");

    private DisposableServer server;

    @Mock
    private ConsoleLogger logger;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
    }

    private ApiClient createClient() {
        return new ApiClient(String.format("http://%s:%d", server.host(), server.port()), API_KEY, logger, 1, 1);
    }

    private ApiClient createClient(OkHttpClient httpClient) {
        return new ApiClient("http://host.tld", API_KEY, logger, () -> httpClient);
    }

    private void assertCommonHeaders(HttpServerRequest request) {
        assertThat(request.requestHeaders().contains(ApiClient.API_KEY_HEADER, "Token " + API_KEY, false))
                .describedAs("Header '%s' must have value '%s'", ApiClient.API_KEY_HEADER, API_KEY)
                .isTrue();
        assertThat(request.requestHeaders().contains(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON, true))
                .describedAs(
                        "Header '%s' must have value '%s'", HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON)
                .isTrue();
    }

    @Test
    void testConnectionTest(JenkinsRule r) throws ApiClientException {
        server = HttpServer.create()
                .host("localhost")
                .port(0)
                .route(routes -> routes.get(ApiClient.PRODUCT_URL, (request, response) -> {
                    assertCommonHeaders(request);
                    return response.status(200).send();
                }))
                .bindNow();

        ApiClient uut = createClient();

        assertThat(uut.testConnection()).isEqualTo(true);
    }

    @Test
    void testConnectionTestWithErrors() throws IOException {
        final var httpClient = mock(OkHttpClient.class);
        final var call = mock(okhttp3.Call.class);
        final var uut = createClient(httpClient);
        when(httpClient.newCall(any(okhttp3.Request.class))).thenReturn(call);
        doThrow(new ConnectException("oops")).when(call).execute();

        assertThatCode(() -> uut.testConnection())
                .hasMessage(Messages.ApiClient_Error_Connection("", ""))
                .hasCauseInstanceOf(ConnectException.class);
        verify(httpClient, times(2)).newCall(any(okhttp3.Request.class));
    }

    @Test
    void testConnectionTestInternalError(JenkinsRule r) {
        server = HttpServer.create()
                .host("localhost")
                .port(0)
                .route(routes -> routes.get(ApiClient.PRODUCT_URL, (request, response) -> response.status(
                                HttpResponseStatus.INTERNAL_SERVER_ERROR)
                        .sendString(Mono.just("something went wrong"))))
                .bindNow();

        ApiClient uut = createClient();

        assertThatCode(() -> uut.testConnection())
                .isInstanceOf(ApiClientException.class)
                .hasNoCause()
                .hasMessage(Messages.ApiClient_Error_Connection(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                        HttpResponseStatus.INTERNAL_SERVER_ERROR.reasonPhrase()));

        verify(logger).log("something went wrong");
    }

    @Test
    void testUploadNoProduct(@TempDir Path tmpWork, JenkinsRule r) throws IOException, InterruptedException {
        server = HttpServer.create()
                .host("localhost")
                .port(0)
                .route(routes -> routes.get(ApiClient.UPLOAD_URL, (request, response) -> {
                    assertCommonHeaders(request);
                    return response.status(400).send();
                }))
                .bindNow();

        File artifact = tmpWork.resolve("report.xml").toFile();
        artifact.createNewFile();
        FilePath artifactPath = new FilePath(artifact);

        ApiClient uut = createClient();

        assertThat(uut.upload(null, null, null, null, null, artifactPath, null, false))
                .isEqualTo(false);
        verify(logger).log(Messages.Builder_Product_NotFound());
    }

    @Test
    void testGetEngagementIdFromDojo(JenkinsRule r) throws ApiClientException {
        server = HttpServer.create()
                .host("localhost")
                .port(0)
                .route(routes -> routes.get(ApiClient.ENGAGEMENT_URL, (request, response) -> {
                    assertCommonHeaders(request);
                    return response.status(200)
                            .sendString(Mono.just("{\"results\": [{\"id\": 10 }], \"prefetch\": {}}"));
                }))
                .bindNow();

        ApiClient uut = createClient();
        assertThat(uut.getEngagementId(null, "test")).isEqualTo("10");
    }
}
