package io.github.fabutnm.homepanel;

/** Tile icons: chosen per item ("icon" in the layout) or from the domain and device class. */
final class Icons {
    static final String[] KEYS = {"bulb", "power", "fan", "snow", "curtains", "lock", "door", "camera", "vacuum",
            "washer", "dishwasher", "drop", "thermo", "cloud", "sparkle", "tv", "gauge", "home"};
    static final String[] NAMES = {"Лампа", "Розетка / выключатель", "Вентилятор", "Кондиционер", "Шторы", "Замок",
            "Дверь", "Камера", "Пылесос", "Стиральная машина", "Посудомойка", "Капля", "Термометр", "Погода",
            "Сцена", "Телевизор", "Датчик", "Дом"};
    private static final int[] RES = {R.drawable.ic_bulb, R.drawable.ic_power, R.drawable.ic_fan, R.drawable.ic_snow,
            R.drawable.ic_curtains, R.drawable.ic_lock, R.drawable.ic_door, R.drawable.ic_camera,
            R.drawable.ic_vacuum, R.drawable.ic_washer, R.drawable.ic_dishwasher, R.drawable.ic_drop,
            R.drawable.ic_thermo, R.drawable.ic_cloud, R.drawable.ic_sparkle, R.drawable.ic_tv, R.drawable.ic_gauge,
            R.drawable.ic_home};

    private Icons() {}

    /** The icon key for an item: its own choice, else one that fits the domain and device class. */
    static String keyFor(String chosen, String domain, String deviceClass, boolean group) {
        if (chosen != null && indexOf(chosen) >= 0) return chosen;
        if (group) return "drop";
        switch (domain) {
            case "light": return "bulb";
            case "switch": case "input_boolean": case "button": return "power";
            case "fan": return "fan";
            case "climate": case "humidifier": case "water_heater": return "snow";
            case "cover": return "curtains";
            case "lock": return "lock";
            case "camera": return "camera";
            case "vacuum": return "vacuum";
            case "scene": case "script": return "sparkle";
            case "media_player": return "tv";
            case "weather": return "cloud";
            case "valve": return "drop";
            default: break;
        }
        if (deviceClass == null) return "gauge";
        switch (deviceClass) {
            case "temperature": return "thermo";
            case "humidity": case "moisture": return "drop";
            case "door": case "window": case "opening": case "garage_door": return "door";
            default: return "gauge";
        }
    }

    static int drawable(String key, boolean unlocked) {
        if ("lock".equals(key) && unlocked) return R.drawable.ic_lock_open;
        int i = indexOf(key);
        return RES[i >= 0 ? i : indexOf("gauge")];
    }

    /** The accent an icon takes when its tile is on (HomeKit: lights yellow, climate blue or orange). */
    static int accent(String key, String state) {
        switch (key) {
            case "bulb": return 0xFFFFB000;
            case "snow": return "heat".equals(state) ? 0xFFFF8A00 : 0xFF0A84FF;
            case "lock": case "door": return 0xFFFF9F0A;
            case "sparkle": return 0xFFBF5AF2;
            case "drop": return 0xFF32ADE6;
            default: return 0xFF0A84FF;
        }
    }

    static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }
}
