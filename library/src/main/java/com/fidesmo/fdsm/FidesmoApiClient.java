/*
 * Copyright (c) 2018 - present Fidesmo AB
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.fidesmo.fdsm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

public class FidesmoApiClient {
    public static final String APIv2 = "https://api.fidesmo.com/v2/";

    public static final String APPS_URL = "apps%s";
    public static final String APP_INFO_URL = "apps/%s";
    public static final String APP_SERVICES_URL = "apps/%s/services";

    public static final String SERVICE_URL = "apps/%s/services/%s";
    public static final String SERVICE_FOR_CARD_URL = "apps/%s/services/%s?cin=%s";
    public static final String SERVICE_RECIPE_URL = "apps/%s/services/%s/recipe";
    public static final String RECIPE_SERVICES_URL = "apps/%s/recipe-services";

    public static final String ELF_URL = "executableLoadFiles";
    public static final String ELF_ID_URL = "executableLoadFiles/%s";

    public static final String SERVICE_DELIVER_URL = "service/deliver";
    public static final String SERVICE_FETCH_URL = "service/fetch";
    public static final String SERVICE_DELIVERY_ERROR_URL = "service/error";

    public static final String CONNECTOR_URL = "connector/json";

    public static final String DEVICES_URL = "devices/%s?batchId=%s";

    private PrintStream apidump;
    private final CloseableHttpClient http;
    private final HttpClientContext context = HttpClientContext.create();
    private final String apiurl;
    protected final ClientAuthentication authentication;

    static DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    static ObjectMapper mapper = new ObjectMapper();

    static {
        // Configure our pretty printer
        DefaultPrettyPrinter.Indenter indenter = new DefaultIndenter("  ", DefaultIndenter.SYS_LF);
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
    }

    @Deprecated
    public FidesmoApiClient() {
        this(APIv2, null, null);
    }

    public FidesmoApiClient(String url, ClientAuthentication authentication, PrintStream apidump) {
        this.apiurl = url;
        this.authentication = authentication;

        this.http = HttpClientBuilder
                .create()
                .useSystemProperties()
                .setUserAgent("fdsm/" + getVersion())
                .build();
        this.apidump = apidump;
    }

    public FidesmoApiClient(ClientAuthentication authentication, PrintStream apidump) {
        this(APIv2, authentication, apidump);
    }

    public CloseableHttpResponse transmit(HttpRequestBase request) throws IOException {
        // XXX: GET/POST get handled in rpc(), this is only for PUT and DELETE
        if (apidump != null && !(request.getMethod().equals("GET") || request.getMethod().equals("POST"))) {
            apidump.println(request.getMethod() + ": " + request.getURI());
        }

        if (authentication != null) {
            request.addHeader(new BasicHeader(HttpHeaders.AUTHORIZATION, authentication.toAuthenticationHeader()));
        }

        CloseableHttpResponse response = http.execute(request, context);
        int responsecode = response.getStatusLine().getStatusCode();
        if (responsecode < 200 || responsecode > 299) {
            String message = response.getStatusLine() + "\n" + IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            response.close();
            throw new HttpResponseException(responsecode, message);
        }
        return response;
    }

    public JsonNode rpc(URI uri) throws IOException {
        return rpc(uri, null);
    }

    public JsonNode rpc(URI uri, JsonNode request) throws IOException {
        final HttpRequestBase req;
        if (request != null) {
            HttpPost post = new HttpPost(uri);
            post.setEntity(new ByteArrayEntity(mapper.writeValueAsBytes(request)));
            req = post;
        } else {
            req = new HttpGet(uri);
        }

        if (apidump != null) {
            apidump.println(req.getMethod() + ": " + req.getURI());
            if (req.getMethod().equals("POST"))
                apidump.println(mapper.writer(printer).writeValueAsString(request));
        }


        req.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.toString());
        req.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());

        try (CloseableHttpResponse response = transmit(req)) {
            if (apidump != null) {
                apidump.println("RECV: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
            }
            if (response.getStatusLine().getStatusCode() == 204) {
                // Empty response
                return null;
            } else {
                // getContent will throw if bad response
                JsonNode json = mapper.readTree(response.getEntity().getContent());
                if (apidump != null) {
                    apidump.println(mapper.writer(printer).writeValueAsString(json));
                }
                return json;
            }
        }
    }

    public URI getURI(String template, String... args) {
        try {
            return new URI(String.format(apiurl + template, (Object[]) args));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid url: " + e.getMessage(), e);
        }
    }

    @Deprecated
    public void setTrace(boolean b) {
        apidump = b ? System.out : null;
    }

    public static String getVersion() {
        try (InputStream versionfile = FidesmoApiClient.class.getResourceAsStream("version.txt")) {
            String version = "unknown-development";
            if (versionfile != null) {
                try (BufferedReader vinfo = new BufferedReader(new InputStreamReader(versionfile, StandardCharsets.US_ASCII))) {
                    version = vinfo.readLine();
                }
            }
            return version;
        } catch (IOException e) {
            return "unknown-error";
        }
    }

    // Prefer English if system locale is not present
    // to convert a possible multilanguage node to a string
    public static String lamei18n(JsonNode n) {
        // For missing values
        if (n == null)
            return "";
        if (n.size() > 0) {
            Map<String, Object> langs = mapper.convertValue(n, new TypeReference<>() {
            });
            Map.Entry<String, Object> first = langs.entrySet().iterator().next();
            return langs.getOrDefault(Locale.getDefault().getLanguage(), langs.getOrDefault("en", first.getValue())).toString();
        } else {
            return n.asText();
        }
    }
}
