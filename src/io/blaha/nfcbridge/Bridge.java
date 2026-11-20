package io.blaha.nfcbridge;

import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcBarcode;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.TagTechnology;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Newline-delimited JSON command server on 127.0.0.1 only. Reached from the computer with
 * `adb forward tcp:47800 tcp:47800`. The first request must be
 * {"cmd":"auth","token":...}; the token lives in this app's private files dir and is
 * readable only by the app itself and by `adb shell run-as` (debug build).
 *
 * Commands that can make a tag permanently unwritable refuse unless the request
 * carries an explicit flag (allow_lock / allow_config / allow_trailer / confirm).
 */
class Bridge {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final MainActivity ui;
    private final String token;
    private final int port;

    private final Object lock = new Object();
    private Tag tag;
    private long tagSeq;
    private long tagAt;
    private TagTechnology conn;
    /** Cached NXP GET_VERSION result for the current tag: null = not asked, empty = not NXP NTAG/UL EV1. */
    private byte[] version;

    private volatile boolean running;
    private ServerSocket server;

    Bridge(MainActivity ui, String token, int port) {
        this.ui = ui;
        this.token = token;
        this.port = port;
    }

    void start() {
        running = true;
        Thread t = new Thread(this::serve, "nfc-bridge");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        synchronized (lock) {
            closeConn();
        }
    }

    void onTag(Tag t) {
        synchronized (lock) {
            closeConn();
            tag = t;
            tagSeq++;
            tagAt = System.currentTimeMillis();
            version = null;
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- server

    private void serve() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2);
        } catch (IOException e) {
            ui.setStatus("Cannot listen: " + e.getMessage());
            return;
        }
        while (running) {
            try (Socket s = server.accept()) {
                s.setTcpNoDelay(true);
                handle(s);
            } catch (IOException e) {
                if (running) ui.addLog("socket: " + e.getMessage());
            }
            if (running) ui.setStatus("Listening on 127.0.0.1:" + port);
        }
    }

    private void handle(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
        Writer out = new OutputStreamWriter(s.getOutputStream(), UTF8);
        boolean authed = false;
        String line;
        while ((line = in.readLine()) != null) {
            JSONObject resp = new JSONObject();
            try {
                JSONObject req = new JSONObject(line);
                resp.put("id", req.opt("id"));
                String cmd = req.optString("cmd");
                if (!authed) {
                    boolean good = "auth".equals(cmd) && MessageDigest.isEqual(
                            token.getBytes(UTF8), req.optString("token").getBytes(UTF8));
                    if (!good) {
                        resp.put("ok", false);
                        resp.put("error", "auth_required");
                        send(out, resp);
                        ui.addLog("rejected unauthenticated client");
                        return;
                    }
                    authed = true;
                    resp.put("ok", true);
                    resp.put("version", MainActivity.VERSION);
                    ui.setStatus("Client connected");
                } else {
                    dispatch(cmd, req, resp);
                    if (!resp.has("ok")) resp.put("ok", true);
                }
            } catch (Exception e) {
                put(resp, "ok", false);
                if (isTagGone(e)) {
                    put(resp, "error", "tag_lost: hold the card to the phone again");
                    synchronized (lock) {
                        closeConn();
                    }
                } else {
                    put(resp, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            send(out, resp);
        }
    }

    private void dispatch(String cmd, JSONObject req, JSONObject resp) throws Exception {
        switch (cmd) {
            case "ping":
                resp.put("version", MainActivity.VERSION);
                return;
            case "status":
                status(resp);
                return;
            case "show":
                ui.showPrompt(req.optString("text"));
                ui.addLog("show: " + req.optString("text"));
                return;
            case "wait_tag":
                waitTag(req, resp);
                return;
            case "info":
                resp.put("tag", info(requireTag()));
                return;
            case "read_ndef":
                readNdef(resp);
                return;
            case "write_ndef":
                writeNdef(req, resp);
                return;
            case "make_readonly":
                makeReadOnly(req, resp);
                return;
            case "transceive":
                transceive(req, resp);
                return;
            case "mfc_read":
                mfcRead(req, resp);
                return;
            case "mfc_write":
                mfcWrite(req, resp);
                return;
            case "close":
                synchronized (lock) {
                    closeConn();
                }
                return;
            default:
                resp.put("ok", false);
                resp.put("error", "unknown_cmd: " + cmd);
        }
    }

    // -------------------------------------------------------------- commands

    private void status(JSONObject resp) throws JSONException {
        resp.put("nfc_enabled", ui.nfcEnabled());
        synchronized (lock) {
            resp.put("has_tag", tag != null);
            resp.put("tag_seq", tagSeq);
            if (tag != null) {
                resp.put("uid", hex(tag.getId()));
                resp.put("tag_age_ms", System.currentTimeMillis() - tagAt);
            }
            resp.put("connected", conn != null && conn.isConnected()
                    ? conn.getClass().getSimpleName() : JSONObject.NULL);
        }
    }

    private void waitTag(JSONObject req, JSONObject resp) throws Exception {
        long timeout = Math.min(Math.max(req.optLong("timeout_ms", 30000), 0), 600000);
        Tag t;
        synchronized (lock) {
            if (req.optBoolean("accept_current") && tag != null && present()) {
                t = tag;
            } else {
                long after = req.has("after_seq") ? req.getLong("after_seq") : tagSeq;
                long deadline = System.currentTimeMillis() + timeout;
                while (tagSeq <= after) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        resp.put("ok", false);
                        resp.put("error", "timeout");
                        return;
                    }
                    lock.wait(left);
                }
                t = tag;
            }
        }
        resp.put("tag", info(t));
    }

    private JSONObject info(Tag t) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("uid", hex(t.getId()));
        synchronized (lock) {
            o.put("seq", t == tag ? tagSeq : -1);
        }
        JSONArray techs = new JSONArray();
        for (String s : t.getTechList()) techs.put(s.substring(s.lastIndexOf('.') + 1));
        o.put("techs", techs);

        NfcA a = NfcA.get(t);
        if (a != null) {
            JSONObject j = new JSONObject();
            j.put("atqa", hex(a.getAtqa()));
            j.put("sak", a.getSak());
            j.put("max_transceive", a.getMaxTransceiveLength());
            o.put("nfca", j);
        }
        MifareUltralight u = MifareUltralight.get(t);
        if (u != null) {
            int ty = u.getType();
            o.put("ultralight_type", ty == MifareUltralight.TYPE_ULTRALIGHT_C ? "ultralight_c"
                    : ty == MifareUltralight.TYPE_ULTRALIGHT ? "ultralight" : "unknown");
        }
        try {
            MifareClassic c = MifareClassic.get(t);
            if (c != null) {
                JSONObject j = new JSONObject();
                j.put("size", c.getSize());
                j.put("sectors", c.getSectorCount());
                j.put("blocks", c.getBlockCount());
                j.put("type", c.getType());
                o.put("mifare_classic", j);
            }
        } catch (RuntimeException e) {
            o.put("mifare_classic_error", String.valueOf(e.getMessage()));
        }
        IsoDep d = IsoDep.get(t);
        if (d != null) {
            JSONObject j = new JSONObject();
            j.put("historical_bytes", hex(d.getHistoricalBytes()));
            j.put("hi_layer_response", hex(d.getHiLayerResponse()));
            j.put("extended_apdu", d.isExtendedLengthApduSupported());
            o.put("isodep", j);
        }
        NfcV v = NfcV.get(t);
        if (v != null) {
            JSONObject j = new JSONObject();
            j.put("dsf_id", v.getDsfId() & 0xFF);
            j.put("response_flags", v.getResponseFlags() & 0xFF);
            o.put("nfcv", j);
        }
        NfcF f = NfcF.get(t);
        if (f != null) {
            JSONObject j = new JSONObject();
            j.put("manufacturer", hex(f.getManufacturer()));
            j.put("system_code", hex(f.getSystemCode()));
            o.put("nfcf", j);
        }
        Ndef n = Ndef.get(t);
        if (n != null) {
            JSONObject j = new JSONObject();
            j.put("type", n.getType());
            j.put("max_size", n.getMaxSize());
            j.put("writable", n.isWritable());
            j.put("can_make_readonly", n.canMakeReadOnly());
            NdefMessage m = n.getCachedNdefMessage();
            if (m != null) j.put("cached_records", records(m));
            o.put("ndef", j);
        }
        o.put("ndef_formatable", NdefFormatable.get(t) != null);
        return o;
    }

    private void readNdef(JSONObject resp) throws Exception {
        Ndef n = use(Ndef.class);
        NdefMessage m = n.getNdefMessage();
        resp.put("records", m == null ? new JSONArray() : records(m));
        resp.put("bytes", m == null ? 0 : m.toByteArray().length);
        resp.put("max_size", n.getMaxSize());
    }

    private void writeNdef(JSONObject req, JSONObject resp) throws Exception {
        JSONArray rs = req.getJSONArray("records");
        NdefRecord[] recs = new NdefRecord[rs.length()];
        for (int i = 0; i < recs.length; i++) recs[i] = record(rs.getJSONObject(i));
        NdefMessage msg = new NdefMessage(recs);
        int size = msg.toByteArray().length;
        resp.put("bytes", size);
        Tag t = requireTag();
        if (Ndef.get(t) != null) {
            Ndef n = use(Ndef.class);
            if (!n.isWritable()) throw new IllegalStateException("tag is read-only");
            if (size > n.getMaxSize())
                throw new IllegalStateException("message is " + size + " bytes; tag holds " + n.getMaxSize());
            n.writeNdefMessage(msg);
            resp.put("method", "write");
        } else if (NdefFormatable.get(t) != null) {
            if (!req.optBoolean("format", true))
                throw new IllegalStateException("tag is blank (not NDEF-formatted); pass format:true");
            NdefFormatable f = use(NdefFormatable.class);
            f.format(msg);
            resp.put("method", "format");
        } else {
            throw new IllegalStateException("tag is neither NDEF nor NDEF-formatable");
        }
        ui.addLog("wrote NDEF " + size + " bytes");
    }

    private void makeReadOnly(JSONObject req, JSONObject resp) throws Exception {
        if (!"PERMANENT".equals(req.optString("confirm")))
            throw new SecurityException("make_readonly is irreversible; pass confirm:\"PERMANENT\"");
        Ndef n = use(Ndef.class);
        if (!n.canMakeReadOnly()) throw new IllegalStateException("this tag cannot be made read-only");
        resp.put("done", n.makeReadOnly());
        ui.addLog("made read-only");
    }

    private void transceive(JSONObject req, JSONObject resp) throws Exception {
        String tech = req.optString("tech", "NfcA");
        byte[] data = unhex(req.getString("hex"));
        int timeout = req.optInt("timeout_ms", 0);
        if ("NfcA".equals(tech) || "MifareUltralight".equals(tech)) guardUltralightWrite(tech, data, req, resp);
        if ("MifareClassic".equals(tech) && data.length >= 2 && (data[0] & 0xFF) == 0xA0)
            guardClassicBlock(data[1] & 0xFF, req);
        byte[] r;
        switch (tech) {
            case "NfcA": {
                NfcA x = use(NfcA.class);
                if (timeout > 0) x.setTimeout(timeout);
                r = x.transceive(data);
                break;
            }
            case "NfcB":
                r = use(NfcB.class).transceive(data);
                break;
            case "NfcF": {
                NfcF x = use(NfcF.class);
                if (timeout > 0) x.setTimeout(timeout);
                r = x.transceive(data);
                break;
            }
            case "NfcV":
                r = use(NfcV.class).transceive(data);
                break;
            case "IsoDep": {
                IsoDep x = use(IsoDep.class);
                if (timeout > 0) x.setTimeout(timeout);
                r = x.transceive(data);
                break;
            }
            case "MifareUltralight": {
                MifareUltralight x = use(MifareUltralight.class);
                if (timeout > 0) x.setTimeout(timeout);
                r = x.transceive(data);
                break;
            }
            case "MifareClassic": {
                MifareClassic x = use(MifareClassic.class);
                if (timeout > 0) x.setTimeout(timeout);
                r = x.transceive(data);
                break;
            }
            default:
                throw new IllegalArgumentException("unknown tech " + tech);
        }
        resp.put("hex", hex(r));
        ui.addLog(tech + " > " + hex(data) + "  < " + hex(r));
    }

    private void mfcRead(JSONObject req, JSONObject resp) throws Exception {
        int block = req.getInt("block");
        MifareClassic c = mfcAuth(req, block);
        resp.put("hex", hex(c.readBlock(block)));
    }

    private void mfcWrite(JSONObject req, JSONObject resp) throws Exception {
        int block = req.getInt("block");
        byte[] data = unhex(req.getString("hex"));
        if (data.length != 16) throw new IllegalArgumentException("a MIFARE Classic block is 16 bytes");
        guardClassicBlock(block, req);
        MifareClassic c = mfcAuth(req, block);
        c.writeBlock(block, data);
        resp.put("written", block);
        ui.addLog("MFC wrote block " + block);
    }

    // ---------------------------------------------------------------- guards

    /**
     * Refuse raw WRITE / COMPATIBILITY WRITE commands aimed at pages whose effect is
     * permanent (lock bytes, one-time-programmable CC) or that set passwords/access,
     * unless the request explicitly allows it.
     */
    private void guardUltralightWrite(String tech, byte[] d, JSONObject req, JSONObject resp) throws Exception {
        if (d.length < 2) return;
        int op = d[0] & 0xFF;
        if (op != 0xA2 && op != 0xA0) return;
        int page = d[1] & 0xFF;
        boolean allowLock = req.optBoolean("allow_lock");
        boolean allowConfig = req.optBoolean("allow_config");
        if (page == 2 && !allowLock)
            throw new SecurityException("page 2 holds the static lock bytes (permanent); pass allow_lock");
        if (page == 3 && !allowLock)
            throw new SecurityException("page 3 is one-time programmable (bits can never be cleared); pass allow_lock");
        int[] layout = layout(tech); // {dynamicLockPage or -1, cfgFirst, cfgLast} or null
        if (layout == null) {
            resp.put("warning", "chip not identified: only pages 2-3 are guarded");
            return;
        }
        if (page == layout[0] && !allowLock)
            throw new SecurityException("page " + page + " holds the dynamic lock bytes (permanent); pass allow_lock");
        if (page >= layout[1] && page <= layout[2] && !allowConfig)
            throw new SecurityException("page " + page + " is configuration (AUTH0/ACCESS/PWD/PACK); pass allow_config");
    }

    /** Page layout from NXP GET_VERSION; null when the chip does not answer or is unknown. */
    private int[] layout(String tech) {
        byte[] v;
        synchronized (lock) {
            v = version;
        }
        if (v == null) {
            try {
                v = "MifareUltralight".equals(tech)
                        ? use(MifareUltralight.class).transceive(new byte[]{0x60})
                        : use(NfcA.class).transceive(new byte[]{0x60});
            } catch (Exception e) {
                v = new byte[0];
                synchronized (lock) {
                    closeConn(); // a NAK halts the tag; the next use() reconnects
                }
            }
            synchronized (lock) {
                version = v;
            }
        }
        if (v.length < 8 || v[1] != 0x04) return null; // vendor must be NXP
        int product = v[2] & 0xFF;
        int storage = v[6] & 0xFF;
        if (product == 0x04) { // NTAG21x
            if (storage == 0x0F) return new int[]{0x28, 0x29, 0x2C}; // NTAG213
            if (storage == 0x11) return new int[]{0x82, 0x83, 0x86}; // NTAG215
            if (storage == 0x13) return new int[]{0xE2, 0xE3, 0xE6}; // NTAG216
        } else if (product == 0x03) { // MIFARE Ultralight EV1
            if (storage == 0x0B) return new int[]{-1, 0x10, 0x13}; // MF0UL11
            if (storage == 0x0E) return new int[]{0x24, 0x25, 0x28}; // MF0UL21
        }
        return null;
    }

    private void guardClassicBlock(int block, JSONObject req) throws Exception {
        if (block == 0 && !req.optBoolean("allow_block0"))
            throw new SecurityException("block 0 is manufacturer data; pass allow_block0");
        MifareClassic c = use(MifareClassic.class);
        int sector = c.blockToSector(block);
        int trailer = c.sectorToBlock(sector) + c.getBlockCountInSector(sector) - 1;
        if (block == trailer && !req.optBoolean("allow_trailer"))
            throw new SecurityException("block " + block + " is the sector trailer (keys + access bits; a bad write "
                    + "locks the sector forever); pass allow_trailer");
    }

    private MifareClassic mfcAuth(JSONObject req, int block) throws Exception {
        MifareClassic c = use(MifareClassic.class);
        int sector = c.blockToSector(block);
        byte[] key = unhex(req.optString("key_hex", "FFFFFFFFFFFF"));
        boolean b = "B".equalsIgnoreCase(req.optString("key_type", "A"));
        boolean ok = b ? c.authenticateSectorWithKeyB(sector, key) : c.authenticateSectorWithKeyA(sector, key);
        if (!ok) {
            synchronized (lock) {
                closeConn(); // failed auth halts the card
            }
            throw new SecurityException("key " + (b ? "B" : "A") + " rejected for sector " + sector);
        }
        return c;
    }

    // --------------------------------------------------------------- helpers

    private Tag requireTag() {
        synchronized (lock) {
            if (tag == null) throw new IllegalStateException("no tag yet: hold one to the phone");
            return tag;
        }
    }

    private <T extends TagTechnology> T use(Class<T> cls) throws Exception {
        synchronized (lock) {
            Tag t = requireTag();
            if (conn != null && cls.isInstance(conn) && conn.isConnected()) return cls.cast(conn);
            closeConn();
            TagTechnology tt = techFor(cls, t);
            if (tt == null) throw new IllegalStateException("tag does not support " + cls.getSimpleName());
            tt.connect();
            conn = tt;
            return cls.cast(tt);
        }
    }

    private static TagTechnology techFor(Class<?> cls, Tag t) {
        if (cls == NfcA.class) return NfcA.get(t);
        if (cls == NfcB.class) return NfcB.get(t);
        if (cls == NfcF.class) return NfcF.get(t);
        if (cls == NfcV.class) return NfcV.get(t);
        if (cls == IsoDep.class) return IsoDep.get(t);
        if (cls == MifareUltralight.class) return MifareUltralight.get(t);
        if (cls == MifareClassic.class) return MifareClassic.get(t);
        if (cls == Ndef.class) return Ndef.get(t);
        if (cls == NdefFormatable.class) return NdefFormatable.get(t);
        if (cls == NfcBarcode.class) return NfcBarcode.get(t);
        return null;
    }

    /** Caller holds the lock. Probes by opening and closing the tag's first technology. */
    private boolean present() {
        closeConn();
        try {
            String first = tag.getTechList()[0];
            Class<?> cls = Class.forName(first);
            TagTechnology tt = techFor(cls, tag);
            if (tt == null) return false;
            tt.connect();
            tt.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void closeConn() {
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // Android 17 throws SecurityException("Tag ... is out of date") for a stale tag.
            }
            conn = null;
        }
    }

    /** A tag that left the field or was re-discovered: TagLostException, or a stale-handle SecurityException. */
    private static boolean isTagGone(Exception e) {
        if (e instanceof TagLostException) return true;
        String m = String.valueOf(e.getMessage());
        return e instanceof SecurityException && m.contains("out of date");
    }

    private static NdefRecord record(JSONObject r) throws Exception {
        String kind = r.getString("kind");
        switch (kind) {
            case "uri":
                return NdefRecord.createUri(r.getString("value"));
            case "text":
                return NdefRecord.createTextRecord(r.optString("lang", "en"), r.getString("value"));
            case "mime":
                return NdefRecord.createMime(r.getString("type"), payload(r));
            case "external":
                return NdefRecord.createExternal(r.getString("domain"), r.getString("type"), payload(r));
            case "aar":
                return NdefRecord.createApplicationRecord(r.getString("value"));
            case "raw":
                return new NdefRecord((short) r.getInt("tnf"), unhex(r.optString("type_hex")),
                        unhex(r.optString("id_hex")), unhex(r.optString("payload_hex")));
            default:
                throw new IllegalArgumentException("unknown record kind " + kind);
        }
    }

    private static byte[] payload(JSONObject r) {
        return r.has("payload_hex") ? unhex(r.optString("payload_hex")) : r.optString("value").getBytes(UTF8);
    }

    static JSONArray records(NdefMessage m) throws JSONException {
        JSONArray a = new JSONArray();
        for (NdefRecord r : m.getRecords()) {
            JSONObject o = new JSONObject();
            o.put("tnf", r.getTnf());
            o.put("type", new String(r.getType(), Charset.forName("ISO-8859-1")));
            o.put("id_hex", hex(r.getId()));
            o.put("payload_hex", hex(r.getPayload()));
            Uri u = r.toUri();
            if (u != null) o.put("uri", u.toString());
            if (r.getTnf() == NdefRecord.TNF_MIME_MEDIA) o.put("mime", r.toMimeType());
            if (r.getTnf() == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(r.getType(), NdefRecord.RTD_TEXT)) {
                byte[] p = r.getPayload();
                if (p.length > 0) {
                    int langLen = p[0] & 0x3F;
                    boolean utf16 = (p[0] & 0x80) != 0;
                    if (1 + langLen <= p.length) {
                        o.put("lang", new String(p, 1, langLen, Charset.forName("US-ASCII")));
                        o.put("text", new String(p, 1 + langLen, p.length - 1 - langLen,
                                Charset.forName(utf16 ? "UTF-16" : "UTF-8")));
                    }
                }
            }
            a.put(o);
        }
        return a;
    }

    private static void send(Writer out, JSONObject resp) throws IOException {
        out.write(resp.toString());
        out.write("\n");
        out.flush();
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        s = s.replaceAll("[\\s:]", "");
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd number of hex digits");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
