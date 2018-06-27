package com.fidesmo.fdsm;

import apdu4j.HexUtils;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public class FidesmoApiClient {
    public static final String APIv2 = "https://api.fidesmo.com/v2/";
    public static final String SERVICE_URL = "apps/%s/services/%s";
    public static final String SERVICE_RECIPE_URL = "apps/%s/services/%s/recipe";
    public static final String RECIPE_SERVICES_URL = "apps/%s/recipe-services";

    public static final String ELF_URL = "executableLoadFiles";
    public static final String ELF_ID_URL = "executableLoadFiles/%s";

    public static final String SERVICE_DELIVER_URL = "service/deliver";
    public static final String SERVICE_FETCH_URL = "service/fetch";
    public static final String CONNECTOR_URL = "connector/json";

    private boolean restdebug = false; // RPC debug
    private final CloseableHttpClient http;
    protected final String appId;
    protected final String appKey;
    private final String apiurl;

    static DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    static ObjectMapper mapper = new ObjectMapper();

    static {
        // Configure our pretty printer
        DefaultPrettyPrinter.Indenter indenter = new DefaultIndenter("  ", DefaultIndenter.SYS_LF);
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);

        if (System.getenv().getOrDefault("FDSM_DEBUG_HTTP", "false").equalsIgnoreCase("true")) {
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
        if (appId != null && appKey != null) {
            if (HexUtils.hex2bin(appId).length != 4)
                throw new IllegalArgumentException("appId must be 4 bytes long (8 hex characters)");
            if (HexUtils.hex2bin(appKey).length != 16)
                throw new IllegalArgumentException("appKey must be 16 bytes long (32 hex characters)");
        }
        this.apiurl = APIv2;
        this.http = HttpClientBuilder.create().useSystemProperties().setUserAgent("fdsm/" + getVersion()).build();
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
        int responsecode = response.getStatusLine().getStatusCode();
        if (responsecode < 200 || responsecode > 299) {
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
            return new URI(String.format(apiurl + template, args));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid stuff: " + e.getMessage(), e);
        }
    }

    public void setTrace(boolean b) {
        restdebug = b;
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
}
