package io.github.fabutnm.homepanel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LogicTest {

    @Test
    public void frameRoundTripsAtEveryLengthEncoding() throws Exception {
        byte[] mask = {1, 2, 3, 4};
        for (int n : new int[]{0, 5, 125, 126, 65535, 65536}) {
            byte[] payload = new byte[n];
            for (int i = 0; i < n; i++) payload[i] = (byte) i;
            WebSocket.Frame f = WebSocket.Frame.read(new ByteArrayInputStream(
                    WebSocket.Frame.encode(WebSocket.OP_TEXT, payload, mask)));
            assertTrue(f.fin);
            assertEquals(WebSocket.OP_TEXT, f.opcode);
            assertArrayEquals("length " + n, payload, f.payload);
        }
    }

    @Test
    public void unmaskedServerFrameIsRead() throws Exception {
        byte[] text = "{\"type\":\"auth_ok\"}".getBytes(StandardCharsets.UTF_8);
        byte[] wire = new byte[2 + text.length];
        wire[0] = (byte) 0x81;
        wire[1] = (byte) text.length;
        System.arraycopy(text, 0, wire, 2, text.length);
        WebSocket.Frame f = WebSocket.Frame.read(new ByteArrayInputStream(wire));
        assertEquals("{\"type\":\"auth_ok\"}", new String(f.payload, StandardCharsets.UTF_8));
    }

    @Test
    public void acceptKeyMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void statesFollowAddChangeAndRemove() throws Exception {
        States s = new States();
        Set<String> c = s.apply(new JSONObject(
                "{\"a\":{\"light.k\":{\"s\":\"off\",\"a\":{\"friendly_name\":\"Кухня\",\"brightness\":10}}}}"));
        assertEquals(Set.of("light.k"), c);
        s.apply(new JSONObject("{\"c\":{\"light.k\":{\"+\":{\"s\":\"on\",\"a\":{\"brightness\":200}}}}}"));
        assertEquals("on", s.get("light.k").state);
        assertEquals("200", s.get("light.k").attr("brightness"));
        assertEquals("Кухня", s.get("light.k").attr("friendly_name"));
        s.apply(new JSONObject("{\"c\":{\"light.k\":{\"-\":{\"a\":[\"brightness\"]}}}}"));
        assertNull(s.get("light.k").attr("brightness"));
        s.apply(new JSONObject("{\"r\":[\"light.k\"]}"));
        assertNull(s.get("light.k"));
    }

    @Test
    public void formatsInRussian() throws Exception {
        States s = new States();
        s.apply(new JSONObject("{\"a\":{"
                + "\"climate.ac\":{\"s\":\"cool\",\"a\":{\"current_temperature\":24,\"temperature\":22}},"
                + "\"cover.c\":{\"s\":\"open\",\"a\":{\"current_position\":40}},"
                + "\"sensor.t\":{\"s\":\"69.8\",\"a\":{\"unit_of_measurement\":\"°F\"}},"
                + "\"sensor.left\":{\"s\":\"20\",\"a\":{\"unit_of_measurement\":\"min\"}},"
                + "\"weather.w\":{\"s\":\"partlycloudy\",\"a\":{\"temperature\":7.5}}}}"));
        assertEquals("24° → 22° · Охлаждение", Format.state("climate", s.get("climate.ac"), null));
        assertEquals("Открыто 40%", Format.state("cover", s.get("cover.c"), null));
        assertEquals("21°", Format.state("sensor", s.get("sensor.t"), null));
        assertEquals("20 мин", Format.state("sensor", s.get("sensor.left"), null));
        assertEquals("7.5° · Переменная облачность", Format.state("weather", s.get("weather.w"), null));
        assertEquals("Выключена", Format.state("sensor", s.get("sensor.left"), Map.of("20", "Выключена")));
        assertTrue(Format.active("lock", "unlocked"));
        assertFalse(Format.active("lock", "locked"));
    }

    @Test
    public void configGivesTheSocketUrlAndTheStartingLayout() throws Exception {
        PanelConfig c = PanelConfig.parse("\uFEFF{\"url\":\"http://192.168.1.2:8123/\",\"token\":\"t\",\"sections\":["
                + "{\"title\":\"A\",\"items\":[{\"entity\":\"light.a\"},{\"entity\":\"sensor.w\",\"detail\":\"sensor.x\"}]},"
                + "{\"title\":\"B\",\"items\":[{\"name\":\"Протечки\",\"any_of\":[\"binary_sensor.1\",\"light.a\"]}]}]}");
        assertEquals("ws://192.168.1.2:8123/api/websocket", c.websocket().toString());
        Layout l = Layout.fromJson(c.raw);
        assertEquals(Arrays.asList("light.a", "sensor.w", "sensor.x", "binary_sensor.1"),
                Arrays.asList(l.entityIds().toArray()));
        assertEquals(5, l.columns);
        assertEquals(Backdrop.DEFAULT, l.background);
    }

    private static Layout threeSections() throws Exception {
        return Layout.fromJson(new JSONObject("{\"sections\":["
                + "{\"title\":\"A\",\"items\":[{\"entity\":\"a.1\"},{\"entity\":\"a.2\"},{\"entity\":\"a.3\"}]},"
                + "{\"title\":\"B\",\"items\":[{\"entity\":\"b.1\"}]},"
                + "{\"title\":\"C\",\"items\":[]}]}"));
    }

    private static List<String> ids(Layout.Section s) {
        String[] out = new String[s.items.size()];
        for (int i = 0; i < out.length; i++) out[i] = s.items.get(i).entity();
        return Arrays.asList(out);
    }

    @Test
    public void movingStopsAtTheEdgeBeforeCrossingIntoTheNextSection() throws Exception {
        Layout l = threeSections();
        Layout.Item a1 = l.sections.get(0).items.get(0);
        assertTrue(l.move(a1, 5));   // a long jump inside the section stops at the last place first
        assertEquals(Arrays.asList("a.2", "a.3", "a.1"), ids(l.sections.get(0)));
        assertTrue(l.move(a1, 1));   // from the last place it goes to the start of the next section
        assertEquals(Arrays.asList("a.1", "b.1"), ids(l.sections.get(1)));
        assertTrue(l.move(a1, -1));  // and back up to the end of the section above
        assertEquals(Arrays.asList("a.2", "a.3", "a.1"), ids(l.sections.get(0)));
        Layout.Item a2 = l.sections.get(0).items.get(0);
        assertFalse(l.move(a2, -1)); // nothing above the first section
    }

    @Test
    public void layoutSurvivesARoundTripWithEditsAndUnknownKeys() throws Exception {
        Layout l = threeSections();
        Layout.Item b1 = l.sections.get(1).items.get(0);
        b1.set("name", "Лампа");
        b1.set("hidden", Boolean.TRUE);
        b1.set("future_key", 7);
        l.moveSection(l.sections.get(1), -1);
        l.columns = 6;
        Layout back = Layout.fromJson(new JSONObject(l.toJson().toString()));
        assertEquals("B", back.sections.get(0).title);
        Layout.Item it = back.sections.get(0).items.get(0);
        assertEquals("Лампа", it.name());
        assertTrue(it.hidden());
        assertEquals(7, it.json.optInt("future_key"));
        assertEquals(6, back.columns);
        b1.set("hidden", null);
        assertFalse(b1.hidden());
    }

    @Test
    public void catalogGroupsByRoomAndSkipsWhatShouldNotBeOffered() throws Exception {
        JSONArray states = new JSONArray("["
                + "{\"entity_id\":\"light.kitchen\",\"attributes\":{\"friendly_name\":\"Кухня\"}},"
                + "{\"entity_id\":\"switch.ac_beep\",\"attributes\":{\"friendly_name\":\"Звук\"}},"
                + "{\"entity_id\":\"scene.kino\",\"attributes\":{\"friendly_name\":\"Кино\"}},"
                + "{\"entity_id\":\"update.fw\",\"attributes\":{}},"
                + "{\"entity_id\":\"switch.hall_reverse\",\"attributes\":{\"friendly_name\":\"Реверс\"}},"
                + "{\"entity_id\":\"light.hall\",\"attributes\":{\"friendly_name\":\"Коридор\"}},"
                + "{\"entity_id\":\"light.added\",\"attributes\":{\"friendly_name\":\"Уже есть\"}}]");
        JSONObject registry = new JSONObject("{\"entities\":["
                + "{\"ei\":\"light.kitchen\",\"ai\":\"kitchen\"},"
                + "{\"ei\":\"switch.ac_beep\",\"di\":\"dev1\",\"ec\":0},"
                + "{\"ei\":\"switch.hall_reverse\",\"di\":\"dev2\"},"
                + "{\"ei\":\"light.hall\",\"di\":\"dev2\"}]}");
        JSONArray areas = new JSONArray("[{\"area_id\":\"kitchen\",\"name\":\"Кухня\"},{\"area_id\":\"hall\",\"name\":\"Коридор\"}]");
        JSONArray devices = new JSONArray("[{\"id\":\"dev2\",\"area_id\":\"hall\"}]");
        Map<String, List<EntityCatalog.Candidate>> rooms =
                EntityCatalog.build(states, registry, areas, devices, Collections.singleton("light.added"));
        assertEquals(Arrays.asList("Кухня", "Коридор", EntityCatalog.NO_ROOM), Arrays.asList(rooms.keySet().toArray()));
        assertEquals("light.kitchen", rooms.get("Кухня").get(0).id);
        assertEquals("light.hall", rooms.get("Коридор").get(0).id);   // room from its device
        assertEquals("Доп. · Выключатель — Реверс", rooms.get("Коридор").get(1).label());   // the lamp's setting, last
        assertEquals(1, rooms.get(EntityCatalog.NO_ROOM).size());       // only the scene: no config switch, no update
        assertEquals("Сцена — Кино", rooms.get(EntityCatalog.NO_ROOM).get(0).label());
    }
}
