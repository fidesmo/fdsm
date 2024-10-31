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
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.text.MessageFormat;

public class FidesmoApiClient {
    public static final String APIv3 = "https://api.fidesmo.com/v3";

    public static final String APPS_URL = "apps%s";
    public static final String APP_INFO_URL = "apps/%s";
    public static final String APP_SERVICES_URL = "apps/%s/services";

    public static final String SERVICE_URL = "apps/%s/services/%s";
    public static final String SERVICE_FOR_CARD_URL = "apps/%s/services/%s?cin=%s";
    public static final String SERVICE_RECIPE_URL = "apps/%s/services/%s/recipe";
    public static final String RECIPE_SERVICES_URL = "apps/%s/recipe-services";

    public static final String CAPFILES_URL = "apps/%s/capfiles";
    public static final String CAPFILES_ID_URL = "apps/%s/capfiles/%s";

    public static final String SERVICE_DELIVER_URL = "service/deliver";
    public static final String SERVICE_FETCH_URL = "service/fetch";
    public static final String SERVICE_DELIVERY_ERROR_URL = "service/error";

    public static final String CONNECTOR_URL = "connector/json";

    public static final String DEVICES_URL = "devices/%s?batchId=%s";
    public static final String DEVICE_IDENTIFY_URL = "devices/identify?cplc=%s";
    public static final String DEVICE_IDENTIFY_WITH_UID_URL = "devices/identify?cplc=%s&uid=%s";

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
        this(APIv3, null, null);
    }

    public FidesmoApiClient(String url, ClientAuthentication authentication, OutputStream apidump) {
        this.apiurl = url;
        this.authentication = authentication;

        this.http = HttpClientBuilder
                .create()
                .useSystemProperties()
                .setUserAgent("fdsm/" + getVersion())
                .build();
        this.apidump = apidump == null ? null : new PrintStream(apidump, true, StandardCharsets.UTF_8);
    }

    public FidesmoApiClient(ClientAuthentication authentication, OutputStream apidump) {
        this(APIv3, authentication, apidump);
    }

    public CloseableHttpResponse get(URI uri) throws IOException {
        HttpGet get = new HttpGet(uri);
        if (apidump != null) {
            apidump.println(get.getMethod() + ": " + get.getURI());
        }
        
        return transmit(get);
    }

    public CloseableHttpResponse put(URI uri, ObjectNode json) throws IOException {
        HttpPut put = new HttpPut(uri);
        put.setEntity(new StringEntity(RecipeGenerator.mapper.writeValueAsString(json), ContentType.APPLICATION_JSON));
        
        if (apidump != null) {
            apidump.println(put.getMethod() + ": " + put.getURI());  
            apidump.println(mapper.writer(printer).writeValueAsString(json));
        }

        return transmit(put);
    }

    public CloseableHttpResponse post(URI uri, ObjectNode json) throws IOException {
        HttpPost post = new HttpPost(uri);
        post.setEntity(new StringEntity(RecipeGenerator.mapper.writeValueAsString(json), ContentType.APPLICATION_JSON));
        
        if (apidump != null) {
            apidump.println(post.getMethod() + ": " + post.getURI());  
            apidump.println(mapper.writer(printer).writeValueAsString(json));            
        }

        return transmit(post);
    }

    public void delete(URI uri) throws IOException {
        HttpDelete delete = new HttpDelete(uri);
        if (apidump != null) {
            apidump.println(delete.getMethod() + ": " + delete.getURI());           
        }
        transmit(delete).close();
    }

    public CloseableHttpResponse transmit(HttpRequestBase request) throws IOException {
        if (authentication != null) {
            request.addHeader(new BasicHeader(HttpHeaders.AUTHORIZATION, authentication.toAuthenticationHeader()));
        }

        CloseableHttpResponse response = http.execute(request, context);
        int responseCode = response.getStatusLine().getStatusCode();
        if (responseCode < 200 || responseCode > 299) {
            String message = response.getStatusLine() + "\n" + IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            response.close();
            throw new HttpResponseException(responseCode, message);
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
        req.setHeader(HttpHeaders.ACCEPT_LANGUAGE, Locale.getDefault().toLanguageTag());

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

    public URI getURI(String template, Object... args) {
        try {
            return new URI(String.format(apiurl + template, args));
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
        if (!n.isEmpty()) {
            boolean isNewFormat = n.has("text");
            if (isNewFormat) {
                return FidesmoApiClient.buildMessageWithParams(n);
            }
            else {
                Map<String, Object> langs = mapper.convertValue(n, new TypeReference<Map<String, Object>>() {
                });
                Map.Entry<String, Object> first = langs.entrySet().iterator().next();
                return langs.getOrDefault(Locale.getDefault().getLanguage(), langs.getOrDefault("en", first.getValue())).toString();
            }
        } else {
            return n.asText();
        }
    }


    public static String buildMessageWithParams(JsonNode n) {

        String text =  n.path("text").asText();

        JsonNode paramsNode = n.path("params");

        // Convert the params array to String[]
        String[] params = new String[paramsNode.size()];
        for (int i = 0; i < paramsNode.size(); i++) {
            params[i] = paramsNode.get(i).asText();
        }

        MessageFormat mf = new MessageFormat(text.replace("'", "''"));
        return mf.format(params);
    }
}
