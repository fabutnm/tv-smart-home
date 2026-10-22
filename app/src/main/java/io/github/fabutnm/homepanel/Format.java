package io.github.fabutnm.homepanel;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Russian state text for a tile. Pure functions, unit tested. */
final class Format {
    private Format() {}

    private static final Map<String, String> COMMON = map(
            "on", "Вкл", "off", "Выкл", "unavailable", "Недоступно", "unknown", "—");
    private static final Map<String, String> COVER = map(
            "open", "Открыто", "closed", "Закрыто", "opening", "Открывается", "closing", "Закрывается");
    private static final Map<String, String> LOCK = map(
            "locked", "Закрыт", "unlocked", "Открыт", "locking", "Закрывается", "unlocking", "Открывается",
            "jammed", "Заклинило", "open", "Открыт", "opening", "Открывается");
    private static final Map<String, String> VACUUM = map(
            "docked", "На базе", "cleaning", "Уборка", "returning", "Едет на базу", "paused", "Пауза",
            "idle", "Ожидание", "error", "Ошибка");
    static final Map<String, String> HVAC = map(
            "off", "Выкл", "cool", "Охлаждение", "heat", "Обогрев", "auto", "Авто", "heat_cool", "Авто",
            "dry", "Осушение", "fan_only", "Вентиляция");
    private static final Map<String, String> WEATHER = map(
            "clear-night", "Ясно", "cloudy", "Облачно", "fog", "Туман", "hail", "Град", "lightning", "Гроза",
            "lightning-rainy", "Гроза, дождь", "partlycloudy", "Переменная облачность", "pouring", "Ливень",
            "rainy", "Дождь", "snowy", "Снег", "snowy-rainy", "Мокрый снег", "sunny", "Солнечно", "windy", "Ветер",
            "windy-variant", "Ветер", "exceptional", "Непогода");
    private static final Map<String, String> MEDIA = map(
            "playing", "Играет", "paused", "Пауза", "idle", "Ожидание", "standby", "Ожидание");
    private static final Map<String, String> UNITS = map(
            "min", "мин", "h", "ч", "hr", "ч", "s", "с", "dy", "дн", "d", "дн");

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Whether the tile shows as "on" (lit): a light that is on, an open cover, an unlocked lock, a running vacuum. */
    static boolean active(String domain, String state) {
        if (state == null) return false;
        switch (domain) {
            case "light": case "switch": case "fan": case "input_boolean":
                return "on".equals(state);
            case "cover":
                return "open".equals(state) || "opening".equals(state);
            case "climate":
                return !"off".equals(state) && !"unavailable".equals(state);
            case "vacuum":
                return "cleaning".equals(state) || "returning".equals(state);
            case "lock":
                return "unlocked".equals(state) || "open".equals(state) || "jammed".equals(state);
            case "media_player":
                return "playing".equals(state);
            default:
                return false;
        }
    }

    /** One line under the tile name. overrides (from config "states") win over everything. */
    static String state(String domain, States.Entity e, Map<String, String> overrides) {
        if (e == null) return "—";
        String s = e.state;
        if (overrides != null && overrides.containsKey(s)) return overrides.get(s);
        if ("unavailable".equals(s) || "unknown".equals(s)) return COMMON.get(s);
        switch (domain) {
            case "cover": {
                Double pos = e.number("current_position");
                String text = COVER.getOrDefault(s, s);
                return pos != null && "open".equals(s) && pos < 100 ? text + " " + Math.round(pos) + "%" : text;
            }
            case "lock":
                return LOCK.getOrDefault(s, s);
            case "vacuum":
                return VACUUM.getOrDefault(s, s);
            case "media_player":
                return MEDIA.getOrDefault(s, COMMON.getOrDefault(s, s));
            case "climate": {
                Double now = e.number("current_temperature");
                Double target = e.number("temperature");
                String mode = HVAC.getOrDefault(s, s);
                if ("off".equals(s)) return now != null ? degrees(now) + " · " + mode : mode;
                String temps = now != null && target != null ? degrees(now) + " → " + degrees(target)
                        : target != null ? degrees(target) : "";
                return temps.isEmpty() ? mode : temps + " · " + mode;
            }
            case "weather": {
                Double t = e.number("temperature");
                String sky = WEATHER.getOrDefault(s, s);
                return t != null ? degrees(t) + " · " + sky : sky;
            }
            case "scene": case "script": case "button":
                return "Запустить";
            case "camera":
                return "Посмотреть";
            case "sensor":
                return value(s, e.attr("unit_of_measurement"));
            default:
                return COMMON.getOrDefault(s, s);
        }
    }

    /** A sensor value with its unit: numbers rounded to one decimal, °F shown as °C. */
    static String value(String raw, String unit) {
        if (raw == null) return "—";
        String u = unit == null ? "" : unit;
        try {
            double v = Double.parseDouble(raw);
            if ("°F".equals(u)) {
                v = (v - 32) * 5 / 9;
                u = "°C";
            }
            String n = v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
            if ("°C".equals(u)) return n + "°";
            u = UNITS.getOrDefault(u, u);
            return u.isEmpty() ? n : u.equals("%") ? n + "%" : n + " " + u;
        } catch (NumberFormatException notNumber) {
            return u.isEmpty() ? raw : raw + " " + UNITS.getOrDefault(u, u);
        }
    }

    static String degrees(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v)) + "°";
    }
}
