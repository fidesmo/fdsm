package com.fidesmo.fdsm;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public class FidesmoApiClient {
    public static final String SERVICE_URL = "https://api.fidesmo.com/v2/apps/%s/services/%s";
    public static final String SERVICE_RECIPE_URL = "https://api.fidesmo.com/v2/apps/%s/services/%s/recipe";
    public static final String RECIPE_SERVICES_URL = "https://api.fidesmo.com/v2/apps/%s/recipe-services";

    public static final String ELF_URL = "https://api.fidesmo.com/v2/executableLoadFiles";
    public static final String ELF_ID_URL = "https://api.fidesmo.com/v2/executableLoadFiles/%s";

    public static final String SERVICE_DELIVER_URL = "https://api.fidesmo.com/v2/service/deliver";
    public static final String SERVICE_FETCH_URL = "https://api.fidesmo.com/v2/service/fetch";
    public static final String CONNECTOR_URL = "https://api.fidesmo.com/v2/connector/json";


    private boolean restdebug = false; // RPC debug
    private final CloseableHttpClient http;
    protected final String appId;
    protected final String appKey;

    static DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    static ObjectMapper mapper = new ObjectMapper();

    static {
        // Configure our pretty printer
        DefaultPrettyPrinter.Indenter indenter = new DefaultIndenter("  ", DefaultIndenter.SYS_LF);
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);

        if (false) {
            System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
            System.setProperty("org.apache.commons.logging.simplelog.showdatetime", "true");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.wire", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.impl.conn", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.impl.client", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.client", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http", "DEBUG");
        }
    }

    public FidesmoApiClient() {
        this(null, null);
    }

    public FidesmoApiClient(String appId, String appKey) {
        this.http = HttpClientBuilder.create().useSystemProperties().setUserAgent("fdsm/18.06.15").build();
        this.appId = appId;
        this.appKey = appKey;
    }

    CloseableHttpResponse transmit(HttpRequestBase request) throws IOException {
        if (appId != null && appKey != null) {
            request.setHeader("app_id", appId);
            request.setHeader("app_key", appKey);
        }
        if (restdebug) {
            System.out.println(request.getMethod() + ": " + request.getURI());
        }

        CloseableHttpResponse response = http.execute(request);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException(response.getStatusLine() + ": \n" + IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8));
        }
        return response;
    }

    JsonNode rpc(URI uri) throws IOException {
        return rpc(uri, null);
    }

    JsonNode rpc(URI uri, JsonNode request) throws IOException {
        HttpRequestBase req;
        if (request != null) {
            HttpPost post = new HttpPost(uri);
            post.setEntity(new StringEntity(request.toString()));
            req = post;
            if (restdebug) {
                System.out.println("POST: " + uri);
                System.out.println(mapper.writer(printer).writeValueAsString(request));
            }
        } else {
            HttpGet get = new HttpGet(uri);
            req = get;
            if (restdebug) {
                System.out.println("GET: " + uri);
            }
        }

        req.setHeader("Accept", ContentType.APPLICATION_JSON.toString());
        req.setHeader("Content-type", ContentType.APPLICATION_JSON.toString());

        CloseableHttpResponse response = transmit(req);

        JsonNode json = mapper.readTree(response.getEntity().getContent());
        if (restdebug) {
            System.out.println("RECV:");
            System.out.println(mapper.writer(printer).writeValueAsString(json));
        }
        return json;
    }

    public URI getURI(String template, String... args) {
        try {
            return new URI(String.format(template, args));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid stuff: " + e.getMessage(), e);
        }
    }

    public void setTrace(boolean b) {
        restdebug = b;
    }
}
