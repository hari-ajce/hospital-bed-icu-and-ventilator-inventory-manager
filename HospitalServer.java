import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Hospital Bed, ICU & Ventilator Inventory Manager - backend (no external libraries, Java 8+).
 * Data structures:
 *   1. 2D grid (Bed[floor][row][col])  - physical floor plan, beds mapped by coordinates
 *   2. Trie                            - medicine / equipment autocomplete, O(k)
 *   3. LRUCache (HashMap + doubly linked list) - recently freed / assigned ICU beds, O(1)
 *   (+ HashMap for O(1) bed lookup, LinkedList used as a FIFO queue of transfer requests)
 * Run:  javac *.java  &&  java HospitalServer     then open http://localhost:8080
 */
public class HospitalServer {
    enum Status { AVAILABLE, OCCUPIED, CLEANING }

    static class Bed {
        String id, ward, patient = "";
        int f, r, c;                        // floor + row/column coordinates in the grid
        boolean vent;                       // ventilator fitted
        Status status = Status.AVAILABLE;
        String json() {
            return obj("id", id, "ward", ward, "f", f, "r", r, "c", c,
                       "status", status.name(), "patient", patient, "vent", vent);
        }
    }

    static class Transfer { int id; String from, patient, toWard, status = "PENDING", note = ""; }

    static final String[] WARDS = {"ICU", "Emergency", "General"};
    static final int FLOORS = 2, ROWS = 5, COLS = 11;

    static final Bed[][][] floors = new Bed[FLOORS][ROWS][COLS];       // one matrix per floor, null = corridor
    static final Map<String, Bed> byId = new HashMap<>();               // O(1) lookup by bed id
    static final Trie trie = new Trie();                                // medicine / equipment search
    static final LRUCache recent = new LRUCache(6);                     // recently freed/assigned ICU beds
    static final LinkedList<Transfer> transfers = new LinkedList<>();   // FIFO queue of transfer requests
    static int nextTransfer = 1;

    // ---------------------------------------------------------------- setup
    static void buildFloorPlan() {
        int patientNo = 0;
        for (int f = 0; f < FLOORS; f++) {
            int[] count = new int[WARDS.length];
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (r == 2 || c == 3 || c == 7) continue;                 // corridors
                    int w = c < 3 ? 0 : c < 7 ? 1 : 2;                         // ward = column block
                    Bed b = new Bed();
                    b.f = f; b.r = r; b.c = c; b.ward = WARDS[w];
                    b.id = WARDS[w].substring(0, 3).toUpperCase() + "-" + (f + 1) + String.format("%02d", ++count[w]);
                    b.vent = w == 0 && (r + c) % 3 != 0;
                    int s = (r * 5 + c * 3 + f * 2) % 7;                       // simple demo data
                    b.status = s < 3 ? Status.OCCUPIED : s == 3 ? Status.CLEANING : Status.AVAILABLE;
                    if (b.status == Status.OCCUPIED) b.patient = "Patient " + (++patientNo);
                    if (b.status == Status.CLEANING) track(b, "FREED");
                    floors[f][r][c] = b;
                    byId.put(b.id, b);
                }
            }
        }
        byId.get("ICU-102").status = Status.AVAILABLE;   // demo: one freed ICU bed is already clean and reassignable
    }

    static void seedStock() {
        String[] stock = {
            "Paracetamol:Analgesic 500mg tablets:Medicine:240", "Amoxicillin:Antibiotic 250mg capsules:Medicine:120",
            "Azithromycin:Antibiotic 500mg tablets:Medicine:80", "Aspirin:Antiplatelet 75mg:Medicine:300",
            "Atropine:Injection 0.6mg/ml:Medicine:45", "Adrenaline:Injection 1mg/ml:Medicine:60",
            "Heparin:Anticoagulant injection:Medicine:50", "Ibuprofen:NSAID 400mg tablets:Medicine:200",
            "Insulin Glargine:Long-acting insulin pen:Medicine:35", "Morphine:Opioid analgesic injection:Medicine:25",
            "Midazolam:Sedative injection:Medicine:30", "Metformin:Antidiabetic 500mg:Medicine:180",
            "Norepinephrine:Vasopressor infusion:Medicine:40", "Propofol:Anaesthetic emulsion:Medicine:28",
            "Salbutamol:Bronchodilator inhaler:Medicine:90", "Ceftriaxone:Antibiotic injection 1g:Medicine:70",
            "VENT-101:ICU ventilator:Equipment:1", "VENT-102:ICU ventilator:Equipment:1",
            "VENT-203:Portable ventilator:Equipment:1", "DEF-011:Defibrillator:Equipment:1",
            "ECG-021:ECG monitor:Equipment:4", "INF-031:Infusion pump:Equipment:9",
            "OXY-041:Oxygen cylinder 10L:Equipment:6", "OXI-051:Pulse oximeter:Equipment:12",
            "SRG-301:Scalpel set:Equipment:15", "SRG-302:Surgical forceps:Equipment:20",
            "SRG-303:Surgical scissors:Equipment:18", "SRG-310:Laparoscope:Equipment:2"
        };
        for (String s : stock) {
            String[] p = s.split(":");
            trie.insert(new Trie.Item(p[0], p[1], p[2], Integer.parseInt(p[3])));
        }
    }

    // ---------------------------------------------------------------- bed operations
    /** Only ICU beds go into the LRU cache. */
    static void track(Bed b, String event) { if (b.ward.equals("ICU")) recent.put(b.id, event); }

    static String assign(String id, String patient) {
        Bed b = byId.get(id);
        patient = patient.trim();
        if (b == null || patient.isEmpty()) return err("Select a bed and enter a patient name");
        if (b.status != Status.AVAILABLE) return err(id + " is not available");
        b.status = Status.OCCUPIED; b.patient = patient;
        track(b, "ASSIGNED");
        return ok(patient + " assigned to " + id);
    }

    static String release(String id) {
        Bed b = byId.get(id);
        if (b == null || b.status != Status.OCCUPIED) return err("Bed is not occupied");
        b.status = Status.CLEANING; b.patient = "";
        track(b, "FREED");
        return ok(id + " released and sent for cleaning");
    }

    static String clean(String id) {
        Bed b = byId.get(id);
        if (b == null || b.status != Status.CLEANING) return err("Bed is not being cleaned");
        b.status = Status.AVAILABLE;
        return ok(id + " is clean and available");
    }

    /** ICU beds freed/assigned within the last hour, most recent first (from the LRU cache). */
    static List<LRUCache.Node> lastHour() {
        List<LRUCache.Node> out = new ArrayList<>();
        for (LRUCache.Node n : recent.toList())
            if (System.currentTimeMillis() - n.time < 3600000L) out.add(n);
        return out;
    }

    /** Instantly give the most recently freed (and clean) ICU bed to a new patient. */
    static String reassign(String patient) {
        for (LRUCache.Node n : lastHour())
            if (byId.get(n.key).status == Status.AVAILABLE) return assign(n.key, patient);
        return err("No ICU bed freed in the last hour is clean and ready yet");
    }

    // ---------------------------------------------------------------- transfers
    static String requestTransfer(String id, String ward) {
        Bed b = byId.get(id);
        if (b == null || b.status != Status.OCCUPIED) return err("Select an occupied bed first");
        if (!Arrays.asList(WARDS).contains(ward) || ward.equals(b.ward)) return err("Choose a different ward");
        for (Transfer x : transfers)
            if (x.from.equals(id) && x.status.equals("PENDING")) return err("This bed already has a pending request");
        Transfer t = new Transfer();
        t.id = nextTransfer++; t.from = id; t.patient = b.patient; t.toWard = ward;
        transfers.addLast(t);
        return ok("Transfer #" + t.id + " requested: " + b.patient + " to " + ward);
    }

    /** Scan the grid for the free bed in `ward` closest (floor, row, column) to the patient's current bed. */
    static Bed nearestFree(String ward, Bed from) {
        Bed best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Bed[][] fl : floors)
            for (Bed[] row : fl)
                for (Bed b : row)
                    if (b != null && b.ward.equals(ward) && b.status == Status.AVAILABLE) {
                        int d = Math.abs(b.f - from.f) * 100 + Math.abs(b.r - from.r) + Math.abs(b.c - from.c);
                        if (d < bestDist) { bestDist = d; best = b; }
                    }
        return best;
    }

    static String decide(String id, boolean approve) {
        Transfer t = null;
        for (Transfer x : transfers) if (String.valueOf(x.id).equals(id)) t = x;
        if (t == null || !t.status.equals("PENDING")) return err("Request is not pending");
        if (!approve) { t.status = "REJECTED"; return ok("Transfer #" + t.id + " rejected"); }
        Bed from = byId.get(t.from);
        if (from.status != Status.OCCUPIED || !from.patient.equals(t.patient)) {
            t.status = "CANCELLED"; t.note = "Patient already left " + from.id;
            return err(t.note);
        }
        Bed to = nearestFree(t.toWard, from);
        if (to == null) { t.note = "Waiting: no free bed in " + t.toWard; return err(t.note); }
        to.status = Status.OCCUPIED; to.patient = from.patient; track(to, "ASSIGNED");
        from.status = Status.CLEANING; from.patient = ""; track(from, "FREED");
        t.status = "COMPLETED"; t.note = from.id + " -> " + to.id;
        return ok(t.patient + " moved to " + to.id);
    }

    // ---------------------------------------------------------------- JSON output
    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\x00-\\x1f]", " ") + "\"";
    }
    static String js(Object o) { return o instanceof String ? q((String) o) : String.valueOf(o); }
    static String obj(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2)
            sb.append(i > 0 ? "," : "").append(q((String) kv[i])).append(':').append(js(kv[i + 1]));
        return sb.append('}').toString();
    }
    static String arr(List<String> items) { return "[" + String.join(",", items) + "]"; }
    static String ok(String m)  { return obj("ok", true, "msg", m); }
    static String err(String m) { return obj("ok", false, "msg", m); }

    static String state() {
        List<String> fl = new ArrayList<>();
        for (Bed[][] floor : floors) {
            List<String> rows = new ArrayList<>();
            for (Bed[] row : floor) {
                List<String> cells = new ArrayList<>();
                for (Bed b : row) cells.add(b == null ? "null" : b.json());
                rows.add(arr(cells));
            }
            fl.add(arr(rows));
        }
        long now = System.currentTimeMillis();
        List<String> rc = new ArrayList<>();
        for (LRUCache.Node n : lastHour())
            rc.add(obj("id", n.key, "event", n.event, "mins", (now - n.time) / 60000));
        List<String> tr = new ArrayList<>();
        for (Transfer t : transfers)
            tr.add(obj("id", t.id, "from", t.from, "patient", t.patient, "toWard", t.toWard, "status", t.status, "note", t.note));
        return "{\"floors\":" + arr(fl) + ",\"recent\":" + arr(rc) + ",\"transfers\":" + arr(tr) + "}";
    }

    static String search(String text) {
        List<String> out = new ArrayList<>();
        text = text.trim();
        if (!text.isEmpty())
            for (Trie.Item it : trie.autocomplete(text, 8))
                out.add(obj("key", it.key, "desc", it.desc, "type", it.type, "qty", it.qty));
        return arr(out);
    }

    // ---------------------------------------------------------------- HTTP
    public static void main(String[] args) throws IOException {
        buildFloorPlan();
        seedStock();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", HospitalServer::route);
        server.start();
        System.out.println("Hospital manager running at http://localhost:" + port);
    }

    /** synchronized: one request at a time, so the shared data structures stay consistent. */
    static synchronized void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (!path.startsWith("/api/")) { serveFile(ex, path); return; }
            Map<String, String> p = params(ex.getRequestURI().getRawQuery());
            String id = p.getOrDefault("id", ""), patient = p.getOrDefault("patient", "");
            String out;
            switch (path) {
                case "/api/state":    out = state(); break;
                case "/api/search":   out = search(p.getOrDefault("q", "")); break;
                case "/api/assign":   out = assign(id, patient); break;
                case "/api/release":  out = release(id); break;
                case "/api/clean":    out = clean(id); break;
                case "/api/reassign": out = reassign(patient); break;
                case "/api/transfer": out = requestTransfer(id, p.getOrDefault("toWard", "")); break;
                case "/api/approve":  out = decide(id, true); break;
                case "/api/reject":   out = decide(id, false); break;
                default:              out = err("Unknown endpoint");
            }
            send(ex, 200, "application/json; charset=utf-8", out.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            send(ex, 500, "application/json; charset=utf-8", err("Server error: " + e).getBytes(StandardCharsets.UTF_8));
        }
    }

    static Map<String, String> params(String qs) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (qs == null) return m;
        for (String kv : qs.split("&")) {
            String[] x = kv.split("=", 2);
            m.put(URLDecoder.decode(x[0], "UTF-8"), x.length > 1 ? URLDecoder.decode(x[1], "UTF-8") : "");
        }
        return m;
    }

    /** Serves only index.html, style.css and app.js from the current folder. */
    static void serveFile(HttpExchange ex, String path) throws IOException {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        String type = name.endsWith(".html") ? "text/html" : name.endsWith(".css") ? "text/css"
                    : name.endsWith(".js") ? "application/javascript" : null;
        Path f = Paths.get(name);
        if (type == null || name.contains("/") || name.contains("\\") || !Files.isRegularFile(f)) {
            send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, type + "; charset=utf-8", Files.readAllBytes(f));
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(body); }
    }
}
