package io.github.fabutnm.homepanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Home Assistant entities that can become tiles, grouped by room: from get_states plus the area, device and
 * entity registries (the same calls the HA frontend makes, so a non-admin user may make them). Entities HA marks
 * as config or diagnostic, or hides, are left out, as are those already on the panel.
 */
final class EntityCatalog {
    static final String NO_ROOM = "Без комнаты";
    /** Domains in the order they are listed within a room, with their Russian names. */
    private static final List<String> DOMAINS = Arrays.asList("scene", "script", "light", "cover", "climate",
            "fan", "humidifier", "water_heater", "lock", "alarm_control_panel", "camera", "vacuum", "media_player",
            "valve", "weather", "switch", "input_boolean", "button", "sensor", "binary_sensor");
    /** A device with one of these is "the" accessory; its switches, buttons and sensors are mostly its settings. */
    private static final List<String> PRIMARY = Arrays.asList("light", "cover", "climate", "fan", "humidifier",
            "water_heater", "lock", "alarm_control_panel", "camera", "vacuum", "media_player", "valve");
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        String[] kv = {"scene", "Сцена", "script", "Скрипт", "light", "Свет", "switch", "Выключатель",
                "fan", "Вентилятор", "input_boolean", "Переключатель", "cover", "Шторы", "climate", "Климат",
                "humidifier", "Увлажнитель", "water_heater", "Водонагреватель", "lock", "Замок",
                "alarm_control_panel", "Охрана", "camera", "Камера", "vacuum", "Пылесос", "media_player", "Медиа",
                "valve", "Кран", "button", "Кнопка", "weather", "Погода", "sensor", "Датчик",
                "binary_sensor", "Датчик"};
        for (int i = 0; i < kv.length; i += 2) NAMES.put(kv[i], kv[i + 1]);
    }

    static final class Candidate {
        final String id;
        final String name;
        final String domain;
        /** A setting of a device that has a main entity (a curtain's motor-reverse switch): listed last. */
        final boolean extra;

        Candidate(String id, String name, String domain, boolean extra) {
            this.id = id;
            this.name = name;
            this.domain = domain;
            this.extra = extra;
        }

        /** "Свет — Кухня Выключатель 1" for the picker list; "Доп. · …" for a device's settings. */
        String label() {
            return (extra ? "Доп. · " : "") + NAMES.getOrDefault(domain, domain) + " — " + name;
        }
    }

    private EntityCatalog() {}

    static String domainName(String domain) {
        return NAMES.getOrDefault(domain, domain);
    }

    /**
     * Rooms in Home Assistant's area order, then {@link #NO_ROOM}; rooms with nothing to add are left out.
     * Any of the registries may be null (a failed call): entities then land in {@link #NO_ROOM} or are kept.
     */
    static Map<String, List<Candidate>> build(JSONArray states, JSONObject entityRegistry, JSONArray areas,
                                              JSONArray devices, Collection<String> exclude) {
        Map<String, String> areaName = new LinkedHashMap<>();
        if (areas != null) for (int i = 0; i < areas.length(); i++) {
            JSONObject a = areas.optJSONObject(i);
            if (a != null) areaName.put(a.optString("area_id"), a.optString("name"));
        }
        Map<String, String> deviceArea = new HashMap<>();
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d != null && !d.isNull("area_id")) deviceArea.put(d.optString("id"), d.optString("area_id"));
        }
        Map<String, JSONObject> registry = new HashMap<>();
        JSONArray list = entityRegistry == null ? null : entityRegistry.optJSONArray("entities");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e != null) registry.put(e.optString("ei"), e);
        }

        // Devices that have a main entity: their other entities are settings, offered last.
        java.util.Set<String> primaryDevices = new java.util.HashSet<>();
        for (JSONObject reg : registry.values()) {
            if (PRIMARY.contains(PanelConfig.domainOf(reg.optString("ei"))) && reg.has("di")) {
                primaryDevices.add(reg.optString("di"));
            }
        }

        Map<String, List<Candidate>> rooms = new LinkedHashMap<>();
        for (String name : areaName.values()) rooms.put(name, new ArrayList<>());
        rooms.put(NO_ROOM, new ArrayList<>());
        if (states != null) for (int i = 0; i < states.length(); i++) {
            JSONObject s = states.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("entity_id");
            String domain = PanelConfig.domainOf(id);
            if (!DOMAINS.contains(domain) || exclude.contains(id)) continue;
            JSONObject reg = registry.get(id);
            if (reg != null && (reg.has("ec") || reg.optBoolean("hb", false))) continue;
            String areaId = reg == null ? null : reg.optString("ai", null);
            if (areaId == null && reg != null) areaId = deviceArea.get(reg.optString("di", ""));
            String room = areaId != null && areaName.containsKey(areaId) ? areaName.get(areaId) : NO_ROOM;
            JSONObject attrs = s.optJSONObject("attributes");
            String name = attrs != null ? attrs.optString("friendly_name", id) : id;
            boolean extra = !PRIMARY.contains(domain) && reg != null && primaryDevices.contains(reg.optString("di", ""));
            rooms.get(room).add(new Candidate(id, name, domain, extra));
        }
        rooms.values().removeIf(List::isEmpty);
        for (List<Candidate> c : rooms.values()) {
            c.sort((a, b) -> {
                if (a.extra != b.extra) return a.extra ? 1 : -1;
                int d = Integer.compare(DOMAINS.indexOf(a.domain), DOMAINS.indexOf(b.domain));
                return d != 0 ? d : a.name.compareToIgnoreCase(b.name);
            });
        }
        return rooms;
    }
}
