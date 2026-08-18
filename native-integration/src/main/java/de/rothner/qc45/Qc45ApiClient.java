package de.rothner.qc45;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Calls the proven qc45api JSP endpoints from inside the charger itself.
 * This deliberately keeps command execution inside the same EVCSD web/API
 * layer that was already used successfully by the former Raspberry bridge.
 */
public final class Qc45ApiClient {
    private final String baseUrl;
    private final String startPath;
    private final String stopPath;
    private final int timeoutMs;

    public Qc45ApiClient(String baseUrl, String startPath, String stopPath, int timeoutMs) {
        this.baseUrl = trimSlash(baseUrl);
        this.startPath = normalizePath(startPath);
        this.stopPath = normalizePath(stopPath);
        this.timeoutMs = timeoutMs;
    }

    public void remoteStart(String idTag, int connector) throws Exception {
        String url = baseUrl + startPath
            + "?connector=" + connector
            + "&idTag=" + enc(idTag == null ? "" : idTag);
        String result = get(url);
        System.out.println("[QC45] API RemoteStart connector=" + connector + " idTag=" + idTag + " response=" + oneLine(result));
        ensureSuccess(result, "RemoteStart");
    }

    public void remoteStop(int connector) throws Exception {
        String url = baseUrl + stopPath + "?connector=" + connector;
        String result = get(url);
        System.out.println("[QC45] API RemoteStop connector=" + connector + " response=" + oneLine(result));
        ensureSuccess(result, "RemoteStop");
    }

    private String get(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        c.setRequestProperty("Connection", "close");
        int status = c.getResponseCode();
        InputStream in = status >= 200 && status < 400 ? c.getInputStream() : c.getErrorStream();
        String body = in == null ? "" : read(in);
        try { if (in != null) in.close(); } catch (Throwable ignored) {}
        c.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("QC45 API HTTP " + status + " from " + target + ": " + oneLine(body));
        }
        return body;
    }

    private static void ensureSuccess(String body, String operation) {
        if (body == null || body.trim().length() == 0) return;
        String v = body.toLowerCase();
        if (v.indexOf("error") >= 0 || v.indexOf("failed") >= 0 || v.indexOf("exception") >= 0 || v.indexOf("rejected") >= 0) {
            throw new IllegalStateException("QC45 API " + operation + " failed: " + oneLine(body));
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[2048];
        int n;
        while ((n = in.read(b)) >= 0) out.write(b, 0, n);
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
    private static String trimSlash(String s) {
        String v = s == null || s.trim().length() == 0 ? "http://127.0.0.1" : s.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
    private static String normalizePath(String s) {
        String v = s == null || s.trim().length() == 0 ? "/" : s.trim();
        return v.charAt(0) == '/' ? v : "/" + v;
    }
    private static String oneLine(String s) {
        if (s == null) return "";
        String v = s.replace('\r', ' ').replace('\n', ' ').trim();
        return v.length() > 500 ? v.substring(0, 500) + "..." : v;
    }
}
