package io.github.fabutnm.homepanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The home panel, HomeKit style: a soft colour backdrop, sections of glass tiles (icon in a circle, name and state),
 * lit tiles turn white. Everything is driven by the remote, including the layout: "Изменить" shows each tile's menu
 * (move, rename, icon, section, hide, remove), adds devices straight from Home Assistant room by room, and edits the
 * sections; the gear sets the backdrop and the columns. The layout is saved on the TV (layout.json).
 * Connected to Home Assistant only while on screen.
 */
public final class PanelActivity extends Activity implements HaClient.Listener {
    private static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    private static final int WHITE = 0xFFFFFFFF, DARK = 0xFF1C1C1E, OK_GREEN = 0xFF30D158, ALARM = 0xFFFF453A;

    private final States states = new States();
    private final Map<Layout.Item, Tile> tiles = new HashMap<>();
    private final Map<Layout.Section, View> sectionHeads = new HashMap<>();
    private PanelConfig config;
    private Layout layout;
    private HaClient client;
    private FrameLayout root;
    private LinearLayout column;
    private TextView summary;
    private TextView status;
    private String statusText = "";
    private int statusColour = 0xB3FFFFFF;
    private boolean loaded;
    private boolean editing;
    /** The tile being moved with the arrows, or null. */
    private Layout.Item carried;
    private float density;
    private ClimateMenu climateMenu;

    // ---- tiles ----------------------------------------------------------------------------------------------

    private final class Tile {
        final Layout.Item item;
        final FrameLayout view;
        final ImageView icon;
        final GradientDrawable circle = new GradientDrawable();
        final TextView name;
        final TextView state;
        final GradientDrawable bg = new GradientDrawable();
        String iconKey = "gauge";
        boolean active, alarm, missing;
        String hvac;

        Tile(Layout.Item item, int width) {
            this.item = item;
            view = new FrameLayout(PanelActivity.this);
            view.setFocusable(true);
            view.setClickable(true);
            view.setLongClickable(true);
            bg.setCornerRadius(dp(22));
            view.setBackground(bg);

            FrameLayout disc = new FrameLayout(PanelActivity.this);
            circle.setShape(GradientDrawable.OVAL);
            disc.setBackground(circle);
            icon = new ImageView(PanelActivity.this);
            disc.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.START);
            dlp.setMargins(dp(14), dp(14), 0, 0);
            view.addView(disc, dlp);

            LinearLayout text = new LinearLayout(PanelActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            name = text(16, WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(false);
            name.setMaxLines(2);
            state = text(14, 0xB3FFFFFF, Typeface.DEFAULT);
            text.addView(name);
            text.addView(state);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
            tlp.setMargins(dp(14), 0, dp(12), dp(12));
            view.addView(text, tlp);

            name.setText(item.name().isEmpty() ? item.entity() : item.name());
            state.setText("…");
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = width;
            lp.height = dp(124);
            lp.setMargins(0, 0, dp(18), dp(18));
            view.setLayoutParams(lp);
            view.setOnClickListener(v -> {
                if (editing) tileMenu(item);
                else act(this);
            });
            view.setOnLongClickListener(v -> {
                tileMenu(item);
                return true;
            });
            view.setOnFocusChangeListener((v, focused) -> {
                paint();
                v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                        .translationZ(focused ? dp(14) : 0).setDuration(140).start();
            });
        }

        void paint() {
            boolean focused = view.isFocused();
            int fill, nameC, stateC, disc, glyph;
            if (alarm) {
                fill = 0xF0FF453A;
                nameC = WHITE;
                stateC = 0xE6FFFFFF;
                disc = 0x33FFFFFF;
                glyph = WHITE;
            } else if (active) {
                int accent = Icons.accent(iconKey, hvac);
                fill = 0xF7FFFFFF;
                nameC = DARK;
                stateC = 0xFF6E6E73;
                disc = (accent & 0x00FFFFFF) | 0x2E000000;
                glyph = accent;
            } else {
                fill = focused ? 0x66FFFFFF : 0x38FFFFFF;
                nameC = WHITE;
                stateC = 0xB3FFFFFF;
                disc = 0x2EFFFFFF;
                glyph = 0xF2FFFFFF;
            }
            bg.setColor(fill);
            if (item == carried) bg.setStroke(dp(3), 0xFFFFD60A);
            else if (focused && !active && !alarm) bg.setStroke(dp(2), 0xE6FFFFFF);   // glass needs an edge to read as focused
            else bg.setStroke(0, 0);
            circle.setColor(disc);
            icon.setColorFilter(glyph);
            name.setTextColor(nameC);
            state.setTextColor(stateC);
            view.setAlpha(editing && (item.hidden() || missing) ? 0.45f : 1f);
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        try {
            config = PanelConfig.read(getFilesDir());
        } catch (Exception e) {
            showMessage(getString(R.string.config_broken, String.valueOf(e.getMessage())));
            return;
        }
        if (config == null) {
            showMessage(getString(R.string.config_missing));
            return;
        }
        layout = Layout.load(getFilesDir(), config);
        root = new FrameLayout(this);
        root.setBackground(Backdrop.create(getResources(), layout.background));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(64), dp(36), dp(64) - dp(18), dp(48));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        scroll.addView(column);
        root.addView(scroll);
        setContentView(root);
        statusText = getString(R.string.connecting);
        rebuild(null);
        client = newClient();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (client != null) client.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (client != null) client.stop();   // nothing stays connected while the panel is not on screen
    }

    private HaClient newClient() {
        return new HaClient(config, layout.entityIds(), this);
    }

    /** After the set of entities changed: a fresh subscription that includes the new ones. */
    private void resubscribe() {
        client.stop();
        states.clear();
        loaded = false;
        client = newClient();
        client.start();
    }

    private void save() {
        try {
            layout.save(getFilesDir());
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ---- layout ---------------------------------------------------------------------------------------------

    /** Builds the whole panel again from the layout and puts the focus back on focus (an item or a section). */
    private void rebuild(Object focus) {
        column.removeAllViews();
        tiles.clear();
        sectionHeads.clear();
        View editPill = header();
        int screen = getResources().getDisplayMetrics().widthPixels;
        int width = (screen - 2 * dp(64) - (layout.columns - 1) * dp(18)) / layout.columns;
        View first = null;
        for (Layout.Section s : layout.sections) {
            List<Layout.Item> shown = new ArrayList<>();
            for (Layout.Item it : s.items) if (editing || (!it.hidden() && (!loaded || present(it)))) shown.add(it);
            if (!editing && shown.isEmpty()) continue;
            TextView head = text(22, WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            head.setText(editing ? s.title + "   ⋯" : s.title);
            head.setPadding(editing ? dp(14) : 0, dp(6), dp(14), dp(6));
            if (editing) {
                makePill(head, () -> sectionMenu(s));
                sectionHeads.put(s, head);
            }
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
            hlp.setMargins(editing ? -dp(14) : 0, dp(18), 0, dp(12));
            column.addView(head, hlp);
            GridLayout grid = grid();
            for (Layout.Item it : shown) {
                Tile t = new Tile(it, width);
                tiles.put(it, t);
                grid.addView(t.view);
                update(t);
                if (first == null) first = t.view;
            }
            if (editing) grid.addView(actionTile(getString(R.string.add), R.drawable.ic_add, width, () -> addTo(s)));
            column.addView(grid);
        }
        if (editing) {
            GridLayout grid = grid();
            grid.addView(actionTile(getString(R.string.new_section), R.drawable.ic_add, width, this::newSection));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
            glp.topMargin = dp(24);
            column.addView(grid, glp);
        } else if (tiles.isEmpty() && loaded) {
            TextView hint = text(20, 0xCCFFFFFF, Typeface.DEFAULT);
            hint.setText(R.string.empty_hint);
            hint.setPadding(0, dp(40), 0, 0);
            column.addView(hint);
        }
        View target = null;
        if (focus instanceof Layout.Item && tiles.containsKey(focus)) target = tiles.get(focus).view;
        else if (focus instanceof Layout.Section) target = sectionHeads.get(focus);
        if (target == null) target = first != null ? first : editPill;
        target.requestFocus();
        updateSummary();
    }

    private GridLayout grid() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(layout.columns);
        grid.setClipChildren(false);   // the focused tile grows past the grid
        grid.setClipToPadding(false);
        return grid;
    }

    /** The title, the summary line, the connection state and the Edit and Settings pills. Returns the Edit pill. */
    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(44, WHITE, Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setText(editing ? getString(R.string.editing_title) : layout.title);
        summary = text(18, 0xD9FFFFFF, Typeface.DEFAULT);
        if (editing) summary.setText(R.string.editing_hint);
        left.addView(title);
        left.addView(summary);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        status = text(15, statusColour, Typeface.DEFAULT);
        status.setText(statusText);
        status.setPadding(0, 0, dp(18), 0);
        row.addView(status);
        TextView edit = pill(editing ? getString(R.string.done) : getString(R.string.edit),
                editing ? 0 : R.drawable.ic_edit, () -> {
                    editing = !editing;
                    carried = null;
                    rebuild(null);
                });
        row.addView(edit);
        TextView gear = pill("", R.drawable.ic_gear, this::settingsMenu);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.leftMargin = dp(12);
        row.addView(gear, glp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.rightMargin = dp(18);
        column.addView(row, rlp);
        return edit;
    }

    private TextView pill(String label, int iconRes, Runnable click) {
        TextView t = text(17, WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(label.isEmpty() ? 12 : 20), dp(10), dp(20), dp(10));
        if (iconRes != 0) {
            Drawable d = getDrawable(iconRes).mutate();
            d.setBounds(0, 0, dp(22), dp(22));
            t.setCompoundDrawables(d, null, null, null);
            t.setCompoundDrawablePadding(label.isEmpty() ? 0 : dp(8));
        }
        makePill(t, click);
        return t;
    }

    /** Glass pill behaviour: translucent, white with dark text while focused. */
    private void makePill(TextView t, Runnable click) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(100));
        t.setBackground(bg);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> click.run());
        View.OnFocusChangeListener paint = (v, focused) -> {
            bg.setColor(focused ? WHITE : 0x33FFFFFF);
            t.setTextColor(focused ? DARK : WHITE);
            for (Drawable d : t.getCompoundDrawables()) if (d != null) d.setTint(focused ? DARK : WHITE);
        };
        t.setOnFocusChangeListener(paint);
        paint.onFocusChange(t, false);
    }

    /** "+ Добавить" / "+ Новый раздел" in edit mode: an outlined tile. */
    private View actionTile(String label, int iconRes, int width, Runnable click) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        v.setBackground(bg);
        v.setFocusable(true);
        v.setClickable(true);
        ImageView i = new ImageView(this);
        i.setImageResource(iconRes);
        v.addView(i, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView t = text(16, WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(label);
        t.setPadding(0, dp(6), 0, 0);
        v.addView(t);
        View.OnFocusChangeListener paint = (x, focused) -> {
            bg.setColor(focused ? WHITE : 0x14FFFFFF);
            bg.setStroke(dp(2), focused ? WHITE : 0x66FFFFFF, dp(8), dp(6));
            t.setTextColor(focused ? DARK : WHITE);
            i.setColorFilter(focused ? DARK : WHITE);
            x.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(140).start();
        };
        v.setOnFocusChangeListener(paint);
        paint.onFocusChange(v, false);
        v.setOnClickListener(x -> click.run());
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = width;
        lp.height = dp(124);
        lp.setMargins(0, 0, dp(18), dp(18));
        v.setLayoutParams(lp);
        return v;
    }

    private void showMessage(String message) {
        TextView t = text(22, WHITE, Typeface.DEFAULT);
        t.setSingleLine(false);
        t.setText(message);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(96), 0, dp(96), 0);
        t.setBackgroundColor(0xFF000000);
        setContentView(t);
    }

    // ---- Home Assistant -------------------------------------------------------------------------------------

    @Override
    public void onConnected() {
        setStatus(getString(R.string.online), OK_GREEN);
    }

    @Override
    public void onEvent(JSONObject event) {
        Set<String> changed = states.apply(event);
        if (!loaded && event.has("a")) {
            loaded = true;
            rebuild(focusedKey());   // now we know which entities exist
            return;
        }
        if (changed.isEmpty()) return;
        for (Tile t : tiles.values()) {
            Layout.Item it = t.item;
            if (changed.contains(it.entity()) || changed.contains(it.detail()) || containsAny(changed, it.anyOf())) {
                update(t);
            }
        }
        updateSummary();
        if (climateMenu != null) climateMenu.refresh();
    }

    @Override
    public void onDisconnected(String reason, boolean authFailed) {
        setStatus(getString(authFailed ? R.string.auth_failed : R.string.offline), ALARM);
    }

    @Override
    public void onCallFailed(String message) {
        Toast.makeText(this, message == null ? getString(R.string.offline)
                : getString(R.string.call_failed, message), Toast.LENGTH_LONG).show();
    }

    private void setStatus(String text, int colour) {
        statusText = text;
        statusColour = colour;
        if (status != null) {
            status.setText(text);
            status.setTextColor(colour);
        }
    }

    private Object focusedKey() {
        View f = getCurrentFocus();
        for (Map.Entry<Layout.Item, Tile> e : tiles.entrySet()) if (e.getValue().view == f) return e.getKey();
        for (Map.Entry<Layout.Section, View> e : sectionHeads.entrySet()) if (e.getValue() == f) return e.getKey();
        return null;
    }

    private boolean present(Layout.Item it) {
        if (!it.anyOf().isEmpty()) {
            for (String id : it.anyOf()) if (states.get(id) != null) return true;
            return false;
        }
        return states.get(it.entity()) != null;
    }

    private static boolean containsAny(Set<String> set, List<String> ids) {
        for (String id : ids) if (set.contains(id)) return true;
        return false;
    }

    private void update(Tile t) {
        Layout.Item it = t.item;
        t.missing = loaded && !present(it);
        if (!it.anyOf().isEmpty()) {
            List<String> raised = new ArrayList<>();
            Map<String, String> labels = it.labels();
            for (String id : it.anyOf()) {
                States.Entity e = states.get(id);
                if (e != null && "on".equals(e.state)) {
                    String n = labels.containsKey(id) ? labels.get(id) : e.attr("friendly_name");
                    raised.add(n == null ? id : n);
                }
            }
            t.alarm = !raised.isEmpty();
            t.active = false;
            t.iconKey = Icons.keyFor(it.icon(), "", null, true);
            t.state.setText(t.alarm ? (it.alarm().isEmpty() ? "Тревога" : it.alarm()) + ": " + TextUtils.join(", ", raised)
                    : it.ok().isEmpty() ? "Норма" : it.ok());
        } else {
            States.Entity e = states.get(it.entity());
            String domain = it.domain();
            String text = Format.state(domain, e, it.states());
            if (!it.detail().isEmpty() && (e == null || !it.detailUnless().contains(e.state))) {
                States.Entity d = states.get(it.detail());
                if (d != null && !"unavailable".equals(d.state) && !"unknown".equals(d.state)) {
                    text = text + " · " + Format.state(PanelConfig.domainOf(it.detail()), d, null);
                }
            }
            t.active = e != null && Format.active(domain, e.state);
            t.alarm = e != null && ("lock".equals(domain) && "jammed".equals(e.state)
                    || "binary_sensor".equals(domain) && "on".equals(e.state) && isAlarmClass(e));
            t.hvac = e == null ? null : e.state;
            t.iconKey = Icons.keyFor(it.icon(), domain, e == null ? null : e.attr("device_class"), false);
            t.icon.setImageResource(Icons.drawable(t.iconKey, e != null && "lock".equals(domain) && t.active));
            if (t.missing) text = getString(R.string.not_in_ha);
            t.state.setText(text);
        }
        if (!it.anyOf().isEmpty()) t.icon.setImageResource(Icons.drawable(t.iconKey, false));
        if (editing && it.hidden()) t.state.setText(R.string.hidden);
        t.paint();
    }

    private static boolean isAlarmClass(States.Entity e) {
        String c = e.attr("device_class");
        return "moisture".equals(c) || "smoke".equals(c) || "gas".equals(c) || "problem".equals(c);
    }

    /** "Свет: включено 3 · 24° · Дверь закрыта", from what the panel shows. */
    private void updateSummary() {
        if (summary == null || editing) return;
        int lights = 0;
        String temp = null, door = null, leak = null;
        for (Layout.Section s : layout.sections) for (Layout.Item it : s.items) {
            if (it.hidden()) continue;
            if (!it.anyOf().isEmpty()) {
                Tile t = tiles.get(it);
                if (t != null && t.alarm && leak == null) leak = t.state.getText().toString();
                continue;
            }
            States.Entity e = states.get(it.entity());
            if (e == null) continue;
            String domain = it.domain();
            String icon = Icons.keyFor(it.icon(), domain, e.attr("device_class"), false);
            if ("bulb".equals(icon) && "on".equals(e.state)) lights++;
            if (temp == null && "climate".equals(domain) && e.number("current_temperature") != null) {
                temp = Format.degrees(e.number("current_temperature"));
            }
            if (door == null && "lock".equals(domain)) {
                door = Format.active("lock", e.state) ? getString(R.string.door_open) : getString(R.string.door_closed);
            }
        }
        List<String> parts = new ArrayList<>();
        if (leak != null) parts.add(leak);
        if (loaded) parts.add(lights > 0 ? getString(R.string.lights_on, lights) : getString(R.string.lights_off));
        if (temp != null) parts.add(temp);
        if (door != null) parts.add(door);
        summary.setText(TextUtils.join(" · ", parts));
        summary.setTextColor(leak != null ? 0xFFFF8A80 : 0xD9FFFFFF);
    }

    // ---- acting on a tile -----------------------------------------------------------------------------------

    private void act(Tile t) {
        Layout.Item it = t.item;
        States.Entity e = states.get(it.entity());
        if (!it.anyOf().isEmpty() || e == null) return;
        if (!client.connected()) {
            onCallFailed(null);
            return;
        }
        String id = it.entity();
        String title = t.name.getText().toString();
        switch (it.domain()) {
            case "light": case "switch": case "fan": case "input_boolean":
                if (it.confirm()) confirm(title + "?", getString(R.string.yes), () -> client.call(it.domain(), "toggle", id, null));
                else client.call(it.domain(), "toggle", id, null);
                break;
            case "scene": case "script":
                client.call(it.domain(), "turn_on", id, null);
                Toast.makeText(this, getString(R.string.started, title), Toast.LENGTH_SHORT).show();
                break;
            case "button":
                client.call("button", "press", id, null);
                break;
            case "cover":
                menu(title, new String[]{"Открыть", "Стоп", "Закрыть"},
                        which -> client.call("cover", new String[]{"open_cover", "stop_cover", "close_cover"}[which], id, null));
                break;
            case "vacuum":
                menu(title, new String[]{"Начать уборку", "Пауза", "На базу"},
                        which -> client.call("vacuum", new String[]{"start", "pause", "return_to_base"}[which], id, null));
                break;
            case "lock":
                if (Format.active("lock", e.state)) client.call("lock", "lock", id, null);
                else confirm(getString(R.string.unlock_question, title), getString(R.string.unlock),
                        () -> client.call("lock", "unlock", id, null));
                break;
            case "climate":
                climateMenu = new ClimateMenu(it, title);
                break;
            case "camera":
                startActivity(new Intent(this, CameraActivity.class)
                        .putExtra(CameraActivity.EXTRA_ENTITY, id).putExtra(CameraActivity.EXTRA_TITLE, title));
                break;
            default:
                break;
        }
    }

    // ---- editing --------------------------------------------------------------------------------------------

    private void tileMenu(Layout.Item item) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.move));
        actions.add(() -> {
            carried = item;
            rebuild(item);
            Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show();
        });
        labels.add(getString(R.string.rename));
        actions.add(() -> textInput(getString(R.string.rename), item.name(), n -> {
            item.set("name", n);
            save();
            rebuild(item);
        }));
        labels.add(getString(R.string.icon));
        actions.add(() -> menu(getString(R.string.icon), Icons.NAMES, which -> {
            item.set("icon", Icons.KEYS[which]);
            save();
            rebuild(item);
        }));
        labels.add(getString(R.string.to_section));
        actions.add(() -> chooseSection(item));
        labels.add(getString(item.hidden() ? R.string.show : R.string.hide));
        actions.add(() -> {
            item.set("hidden", item.hidden() ? null : Boolean.TRUE);
            save();
            if (!editing && item.hidden()) Toast.makeText(this, R.string.hidden_hint, Toast.LENGTH_LONG).show();
            rebuild(editing ? item : neighbour(item));
        });
        labels.add(getString(R.string.remove));
        actions.add(() -> confirm(getString(R.string.remove_question, item.name()), getString(R.string.remove), () -> {
            Object next = neighbour(item);
            layout.remove(item);
            save();
            rebuild(next);
        }));
        menu(item.name().isEmpty() ? item.entity() : item.name(), labels.toArray(new String[0]),
                which -> actions.get(which).run());
    }

    /** The tile after this one (or before, at the end), for the focus once it is gone. */
    private Object neighbour(Layout.Item item) {
        Layout.Section s = layout.sectionOf(item);
        if (s == null) return null;
        int i = s.items.indexOf(item);
        if (i + 1 < s.items.size()) return s.items.get(i + 1);
        return i > 0 ? s.items.get(i - 1) : null;
    }

    private void chooseSection(Layout.Item item) {
        List<Layout.Section> others = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Layout.Section s : layout.sections) {
            if (s.items.contains(item)) continue;
            others.add(s);
            names.add(s.title);
        }
        names.add(getString(R.string.new_section_dots));
        menu(getString(R.string.to_section), names.toArray(new String[0]), which -> {
            if (which < others.size()) {
                layout.moveTo(item, others.get(which));
                save();
                rebuild(item);
            } else {
                textInput(getString(R.string.new_section), "", title -> {
                    Layout.Section s = new Layout.Section(title);
                    layout.sections.add(s);
                    layout.moveTo(item, s);
                    save();
                    rebuild(item);
                });
            }
        });
    }

    private void sectionMenu(Layout.Section s) {
        String[] rows = {getString(R.string.rename), getString(R.string.add_devices), getString(R.string.section_up),
                getString(R.string.section_down), getString(R.string.section_delete)};
        menu(s.title, rows, which -> {
            switch (which) {
                case 0:
                    textInput(getString(R.string.rename), s.title, t -> {
                        s.title = t;
                        save();
                        rebuild(s);
                    });
                    break;
                case 1:
                    addTo(s);
                    break;
                case 2: case 3:
                    if (layout.moveSection(s, which == 2 ? -1 : 1)) {
                        save();
                        rebuild(s);
                    }
                    break;
                default:
                    confirm(getString(R.string.section_delete_question, s.title, s.items.size()),
                            getString(R.string.remove), () -> {
                                layout.sections.remove(s);
                                save();
                                rebuild(null);
                            });
                    break;
            }
        });
    }

    private void newSection() {
        textInput(getString(R.string.new_section), "", title -> {
            Layout.Section s = new Layout.Section(title);
            layout.sections.add(s);
            save();
            rebuild(s);
            addTo(s);
        });
    }

    /** Room, then devices with check boxes, straight from Home Assistant; the picked ones join section s. */
    private void addTo(Layout.Section s) {
        if (client == null || !client.connected()) {
            onCallFailed(null);
            return;
        }
        Toast.makeText(this, R.string.loading_devices, Toast.LENGTH_SHORT).show();
        client.request("get_states", st -> {
            if (st == null) {
                onCallFailed(null);
                return;
            }
            client.request("config/entity_registry/list_for_display", reg ->
                    client.request("config/area_registry/list", areas ->
                            client.request("config/device_registry/list", devices -> {
                                Map<String, List<EntityCatalog.Candidate>> rooms = EntityCatalog.build(
                                        st.optJSONArray("result"),
                                        reg == null ? null : reg.optJSONObject("result"),
                                        areas == null ? null : areas.optJSONArray("result"),
                                        devices == null ? null : devices.optJSONArray("result"),
                                        layout.entityIds());
                                chooseRoom(s, rooms);
                            })));
        });
    }

    private void chooseRoom(Layout.Section s, Map<String, List<EntityCatalog.Candidate>> rooms) {
        if (rooms.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_add, Toast.LENGTH_LONG).show();
            return;
        }
        List<String> keys = new ArrayList<>(rooms.keySet());
        String[] rows = new String[keys.size()];
        for (int i = 0; i < rows.length; i++) rows[i] = keys.get(i) + "  ·  " + rooms.get(keys.get(i)).size();
        menu(getString(R.string.pick_room, s.title), rows, which -> chooseDevices(s, rooms, keys.get(which)));
    }

    private void chooseDevices(Layout.Section s, Map<String, List<EntityCatalog.Candidate>> rooms, String room) {
        List<EntityCatalog.Candidate> list = rooms.get(room);
        String[] rows = new String[list.size()];
        for (int i = 0; i < rows.length; i++) rows[i] = list.get(i).label();
        boolean[] checked = new boolean[rows.length];
        new AlertDialog.Builder(this, DIALOG)
                .setTitle(room)
                .setMultiChoiceItems(rows, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(R.string.add, (d, w) -> {
                    Layout.Item first = null;
                    for (int i = 0; i < checked.length; i++) {
                        if (!checked[i]) continue;
                        EntityCatalog.Candidate c = list.get(i);
                        Layout.Item it = Layout.Item.of(c.id, c.name);
                        s.items.add(it);
                        if (first == null) first = it;
                    }
                    if (first == null) return;
                    save();
                    resubscribe();
                    rebuild(first);
                })
                .setNeutralButton(R.string.back, (d, w) -> chooseRoom(s, rooms))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void settingsMenu() {
        String[] rows = {
                getString(R.string.background_row, Backdrop.NAMES[Backdrop.index(layout.background)]),
                getString(R.string.columns_row, layout.columns),
                getString(editing ? R.string.done : R.string.edit_panel),
                getString(R.string.reset_layout)};
        menu(getString(R.string.settings), rows, which -> {
            switch (which) {
                case 0:
                    menu(getString(R.string.background), Backdrop.NAMES, b -> {
                        layout.background = Backdrop.KEYS[b];
                        root.setBackground(Backdrop.create(getResources(), layout.background));
                        save();
                    });
                    break;
                case 1: {
                    String[] options = new String[Layout.MAX_COLUMNS - Layout.MIN_COLUMNS + 1];
                    for (int i = 0; i < options.length; i++) options[i] = String.valueOf(Layout.MIN_COLUMNS + i);
                    menu(getString(R.string.columns), options, c -> {
                        layout.columns = Layout.MIN_COLUMNS + c;
                        save();
                        rebuild(focusedKey());
                    });
                    break;
                }
                case 2:
                    editing = !editing;
                    carried = null;
                    rebuild(null);
                    break;
                default:
                    confirm(getString(R.string.reset_question), getString(R.string.reset), () -> {
                        Layout.reset(getFilesDir());
                        layout = Layout.load(getFilesDir(), config);
                        root.setBackground(Backdrop.create(getResources(), layout.background));
                        resubscribe();
                        rebuild(null);
                    });
                    break;
            }
        });
    }

    /** While a tile is carried the arrows move it and OK or Back puts it down. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (carried == null) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        boolean put = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BACK;
        int delta = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : code == KeyEvent.KEYCODE_DPAD_UP ? -layout.columns : code == KeyEvent.KEYCODE_DPAD_DOWN ? layout.columns : 0;
        if (!put && delta == 0) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        Layout.Item item = carried;
        if (put) {
            carried = null;
            save();
            rebuild(item);
        } else if (layout.move(item, delta)) {
            rebuild(item);
        }
        return true;
    }

    // ---- dialogs --------------------------------------------------------------------------------------------

    private interface Choice {
        void pick(int which);
    }

    private void menu(String title, String[] rows, Choice choice) {
        new AlertDialog.Builder(this, DIALOG)
                .setTitle(title)
                .setItems(rows, (d, which) -> choice.pick(which))
                .show();
    }

    /** A yes/no question with Cancel focused, so a stray OK press does nothing. */
    private void confirm(String question, String yes, Runnable action) {
        AlertDialog dialog = new AlertDialog.Builder(this, DIALOG)
                .setTitle(question)
                .setPositiveButton(yes, (d, w) -> action.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
        Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (cancel != null) cancel.requestFocus();
    }

    /** One line of text with the TV's keyboard. */
    private void textInput(String title, String value, Consumer<String> done) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setSelection(value.length());
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this, DIALOG)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.done, (d, w) -> {
                    String t = input.getText().toString().trim();
                    if (!t.isEmpty()) done.accept(t);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, action, ev) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    /** On/off, target temperature −/+ and the modes the unit offers; stays open and follows the state. */
    private final class ClimateMenu {
        final Layout.Item item;
        final TextView line;
        final Button power;

        ClimateMenu(Layout.Item item, String title) {
            this.item = item;
            LinearLayout box = new LinearLayout(PanelActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(24), dp(8), dp(24), dp(8));
            line = text(22, WHITE, Typeface.DEFAULT);
            line.setPadding(0, 0, 0, dp(12));
            box.addView(line);
            LinearLayout row = new LinearLayout(PanelActivity.this);
            power = button(row, "", v -> {
                States.Entity e = states.get(item.entity());
                boolean off = e == null || "off".equals(e.state);
                client.call("climate", off ? "turn_on" : "turn_off", item.entity(), null);
            });
            button(row, "−", v -> step(-1));
            button(row, "+", v -> step(+1));
            box.addView(row);
            States.Entity e = states.get(item.entity());
            JSONArray modes = e == null ? null : e.attrs.optJSONArray("hvac_modes");
            if (modes != null) {
                LinearLayout modeRow = null;
                int inRow = 0;
                for (int i = 0; i < modes.length(); i++) {
                    String m = modes.optString(i);
                    if ("off".equals(m)) continue;
                    if (modeRow == null || inRow == 3) {   // three modes a row: four do not fit the dialog
                        modeRow = new LinearLayout(PanelActivity.this);
                        box.addView(modeRow);
                        inRow = 0;
                    }
                    inRow++;
                    button(modeRow, Format.HVAC.getOrDefault(m, m), v -> {
                        try {
                            client.call("climate", "set_hvac_mode", item.entity(), new JSONObject().put("hvac_mode", m));
                        } catch (JSONException ignored) {
                            // a constant key and a string value cannot fail
                        }
                    });
                }
            }
            new AlertDialog.Builder(PanelActivity.this, DIALOG)
                    .setTitle(title)
                    .setView(box)
                    .setOnDismissListener(d -> climateMenu = null)
                    .show();
            refresh();
            power.requestFocus();
        }

        void refresh() {
            States.Entity e = states.get(item.entity());
            line.setText(Format.state("climate", e, null));
            power.setText(e == null || "off".equals(e.state) ? "Включить" : "Выключить");
        }

        void step(int dir) {
            States.Entity e = states.get(item.entity());
            if (e == null) return;
            Double target = e.number("temperature");
            Double step = e.number("target_temp_step");
            Double min = e.number("min_temp"), max = e.number("max_temp");
            double next = (target != null ? target : 24) + dir * (step != null ? step : 1);
            if (min != null) next = Math.max(min, next);
            if (max != null) next = Math.min(max, next);
            try {
                client.call("climate", "set_temperature", item.entity(), new JSONObject().put("temperature", next));
            } catch (JSONException ignored) {
                // a finite double cannot fail
            }
        }
    }

    private Button button(LinearLayout row, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setOnClickListener(click);
        GradientDrawable face = new GradientDrawable();
        face.setCornerRadius(dp(12));
        b.setBackground(face);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        View.OnFocusChangeListener paint = (v, focused) -> {
            face.setColor(focused ? WHITE : 0xFF3A464D);
            b.setTextColor(focused ? 0xFF111111 : WHITE);
        };
        b.setOnFocusChangeListener(paint);
        paint.onFocusChange(b, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), dp(8));
        row.addView(b, lp);
        return b;
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private TextView text(float sp, int colour, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(face);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
