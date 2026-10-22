package io.github.fabutnm.homepanel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * config.json, written by the install script through ConfigProvider: the server ("url"), the "token", and the
 * starting layout ("title", "columns", "sections"; see {@link Layout}). Once the layout is edited on the TV it lives
 * in layout.json instead and this file's sections only come back with "Reset layout".
 */
final class PanelConfig {
    final URI base;
    final String token;
    /** The whole file, for the starting layout. */
    final JSONObject raw;

    private PanelConfig(URI base, String token, JSONObject raw) {
        this.base = base;
        this.token = token;
        this.raw = raw;
    }

    /** config.json from the app's files directory; null when it was never written. */
    static PanelConfig read(File dir) throws IOException, JSONException {
        File f = new File(dir, ConfigProvider.FILE_NAME);
        if (!f.isFile()) return null;
        if (f.length() > 256 * 1024) throw new IOException("config.json is larger than 256 KB");
        return parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    static PanelConfig parse(String text) throws JSONException {
        JSONObject o = new JSONObject(text.startsWith("\uFEFF") ? text.substring(1) : text);
        String url = o.getString("url").trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        URI base = URI.create(url);
        if (base.getHost() == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))) {
            throw new JSONException("url must be http(s)://host:port");
        }
        String token = o.getString("token").trim();
        if (token.isEmpty()) throw new JSONException("token is empty");
        return new PanelConfig(base, token, o);
    }

    static String domainOf(String entityId) {
        int dot = entityId.indexOf('.');
        return dot > 0 ? entityId.substring(0, dot) : "";
    }

    URI websocket() {
        String scheme = "https".equals(base.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + base.getRawAuthority() + "/api/websocket");
    }
}
