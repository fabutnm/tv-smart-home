package io.github.fabutnm.homepanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the panel shows and in which order: sections of items, the tile columns and the background. Starts from
 * config.json and, once edited with the remote, lives in layout.json in the app's files, which a config push from
 * the PC leaves alone. Items keep their JSON, so keys the editor does not know survive an edit.
 *
 * <pre>
 * { "columns": 5, "background": "sunset", "sections": [ { "title": "Свет", "items": [
 *     { "entity": "light.kitchen_1", "name": "Кухня 1", "icon": "bulb", "hidden": false },
 *     { "entity": "sensor.washer_status", "name": "Стиральная", "detail": "sensor.washer_left",
 *       "detail_unless": ["Off"], "states": { "Off": "Выключена" } },
 *     { "name": "Протечки", "any_of": ["binary_sensor.leak_1"], "ok": "Сухо", "alarm": "Протечка",
 *       "labels": { "binary_sensor.leak_1": "кухня" } } ] } ] }
 * </pre>
 */
final class Layout {
    static final String FILE_NAME = "layout.json";
    static final int MIN_COLUMNS = 4, MAX_COLUMNS = 6;

    static final class Item {
        final JSONObject json;

        Item(JSONObject json) {
            this.json = json;
        }

        static Item of(String entity, String name) {
            JSONObject o = new JSONObject();
            try {
                o.put("entity", entity).put("name", name);
            } catch (JSONException e) {
                throw new IllegalArgumentException(e);
            }
            return new Item(o);
        }

        String entity() { return json.optString("entity", "").trim(); }
        String name() { return json.optString("name", "").trim(); }
        String detail() { return json.optString("detail", "").trim(); }
        String icon() { return json.optString("icon", "").trim(); }
        String ok() { return json.optString("ok", ""); }
        String alarm() { return json.optString("alarm", ""); }
        boolean hidden() { return json.optBoolean("hidden", false); }
        boolean confirm() { return json.optBoolean("confirm", false); }
        List<String> anyOf() { return strings(json.optJSONArray("any_of")); }
        List<String> detailUnless() { return strings(json.optJSONArray("detail_unless")); }
        Map<String, String> states() { return map(json.optJSONObject("states")); }
        Map<String, String> labels() { return map(json.optJSONObject("labels")); }

        String domain() {
            return PanelConfig.domainOf(entity());
        }

        void set(String key, Object value) {
            try {
                if (value == null) json.remove(key);
                else json.put(key, value);
            } catch (JSONException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    static final class Section {
        String title;
        final List<Item> items = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }
    }

    final List<Section> sections = new ArrayList<>();
    String title = "Дом";
    int columns = 5;
    String background = Backdrop.DEFAULT;

    static Layout fromJson(JSONObject o) {
        Layout l = new Layout();
        l.title = o.optString("title", l.title);
        l.columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, o.optInt("columns", l.columns)));
        l.background = o.optString("background", l.background);
        JSONArray arr = o.optJSONArray("sections");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            Section section = new Section(s.optString("title", ""));
            JSONArray items = s.optJSONArray("items");
            if (items != null) for (int j = 0; j < items.length(); j++) {
                JSONObject it = items.optJSONObject(j);
                if (it != null) section.items.add(new Item(it));
            }
            l.sections.add(section);
        }
        return l;
    }

    JSONObject toJson() {
        try {
            JSONArray arr = new JSONArray();
            for (Section s : sections) {
                JSONArray items = new JSONArray();
                for (Item it : s.items) items.put(it.json);
                arr.put(new JSONObject().put("title", s.title).put("items", items));
            }
            return new JSONObject().put("title", title).put("columns", columns).put("background", background)
                    .put("sections", arr);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** layout.json when it exists and parses, otherwise the layout in config.json. */
    static Layout load(File dir, PanelConfig config) {
        File f = new File(dir, FILE_NAME);
        if (f.isFile()) {
            try {
                return fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            } catch (IOException | JSONException e) {
                // a damaged edit falls back to the pushed layout rather than an empty panel
            }
        }
        return fromJson(config.raw);
    }

    /** Written next to the target and renamed over it, so a power cut never leaves half a file. */
    void save(File dir) throws IOException {
        File tmp = new File(dir, FILE_NAME + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(toJson().toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir, FILE_NAME))) throw new IOException("cannot replace " + FILE_NAME);
    }

    static boolean reset(File dir) {
        File f = new File(dir, FILE_NAME);
        return !f.exists() || f.delete();
    }

    /** Every entity the panel shows or reads, for subscribe_entities. */
    Set<String> entityIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Section s : sections) for (Item it : s.items) {
            if (!it.entity().isEmpty()) ids.add(it.entity());
            if (!it.detail().isEmpty()) ids.add(it.detail());
            ids.addAll(it.anyOf());
        }
        return ids;
    }

    Section sectionOf(Item item) {
        for (Section s : sections) if (s.items.contains(item)) return s;
        return null;
    }

    /**
     * Moves an item by delta places. Inside its section it stops at the first or last place before it crosses over;
     * from there it goes to the end of the section above or the start of the one below. False when it cannot move.
     */
    boolean move(Item item, int delta) {
        Section s = sectionOf(item);
        if (s == null || delta == 0) return false;
        int i = s.items.indexOf(item);
        int n = s.items.size();
        int target = i + delta;
        if (target < 0 && i > 0) target = 0;
        if (target >= n && i < n - 1) target = n - 1;
        if (target >= 0 && target < n) {
            s.items.remove(i);
            s.items.add(target, item);
            return true;
        }
        int si = sections.indexOf(s);
        int next = si + (delta < 0 ? -1 : 1);
        if (next < 0 || next >= sections.size()) return false;
        s.items.remove(i);
        Section other = sections.get(next);
        if (delta < 0) other.items.add(item);
        else other.items.add(0, item);
        return true;
    }

    void moveTo(Item item, Section target) {
        Section s = sectionOf(item);
        if (s == null || s == target) return;
        s.items.remove(item);
        target.items.add(item);
    }

    boolean moveSection(Section s, int delta) {
        int i = sections.indexOf(s);
        int target = i + delta;
        if (i < 0 || target < 0 || target >= sections.size()) return false;
        sections.remove(i);
        sections.add(target, s);
        return true;
    }

    void remove(Item item) {
        Section s = sectionOf(item);
        if (s != null) s.items.remove(item);
    }

    private static List<String> strings(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i));
        return Collections.unmodifiableList(list);
    }

    private static Map<String, String> map(JSONObject o) {
        Map<String, String> m = new HashMap<>();
        if (o != null) for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, o.optString(k));
        }
        return Collections.unmodifiableMap(m);
    }
}
