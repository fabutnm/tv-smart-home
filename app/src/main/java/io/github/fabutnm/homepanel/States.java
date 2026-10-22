package io.github.fabutnm.homepanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Entity states as Home Assistant's subscribe_entities sends them: "a" adds full entities, "c" carries changes
 * ("+" sets the state and merges attributes, "-" drops attributes), "r" removes entities. Keys are compressed:
 * "s" state, "a" attributes. Only the main thread touches this.
 */
final class States {
    static final class Entity {
        String state;
        final JSONObject attrs;

        Entity(String state, JSONObject attrs) {
            this.state = state;
            this.attrs = attrs;
        }

        String attr(String key) {
            Object v = attrs.opt(key);
            return v == null || v == JSONObject.NULL ? null : String.valueOf(v);
        }

        Double number(String key) {
            Object v = attrs.opt(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            try {
                return v == null ? null : Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private final Map<String, Entity> entities = new HashMap<>();

    Entity get(String id) {
        return entities.get(id);
    }

    void clear() {
        entities.clear();
    }

    /** Applies one subscribe_entities event; returns the ids that changed. */
    Set<String> apply(JSONObject event) {
        Set<String> changed = new LinkedHashSet<>();
        JSONObject added = event.optJSONObject("a");
        if (added != null) {
            for (Iterator<String> it = added.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject e = added.optJSONObject(id);
                if (e == null) continue;
                JSONObject attrs = e.optJSONObject("a");
                entities.put(id, new Entity(e.optString("s", "unknown"), attrs != null ? attrs : new JSONObject()));
                changed.add(id);
            }
        }
        JSONObject changes = event.optJSONObject("c");
        if (changes != null) {
            for (Iterator<String> it = changes.keys(); it.hasNext(); ) {
                String id = it.next();
                Entity e = entities.get(id);
                JSONObject diff = changes.optJSONObject(id);
                if (e == null || diff == null) continue;
                JSONObject plus = diff.optJSONObject("+");
                if (plus != null) {
                    if (plus.has("s")) e.state = plus.optString("s", e.state);
                    JSONObject attrs = plus.optJSONObject("a");
                    if (attrs != null) {
                        for (Iterator<String> k = attrs.keys(); k.hasNext(); ) {
                            String key = k.next();
                            try {
                                e.attrs.put(key, attrs.opt(key));
                            } catch (org.json.JSONException ignored) {
                                // only a non-finite number throws, and parsed JSON has none
                            }
                        }
                    }
                }
                JSONObject minus = diff.optJSONObject("-");
                JSONArray gone = minus == null ? null : minus.optJSONArray("a");
                if (gone != null) for (int i = 0; i < gone.length(); i++) e.attrs.remove(gone.optString(i));
                changed.add(id);
            }
        }
        JSONArray removed = event.optJSONArray("r");
        if (removed != null) {
            for (int i = 0; i < removed.length(); i++) {
                if (entities.remove(removed.optString(i)) != null) changed.add(removed.optString(i));
            }
        }
        return changed;
    }
}
