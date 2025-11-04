package backend;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;

/**
 * Upgraded Server.java for Alumni Search Engine
 * - Multi-field dynamic search (id, name, department, year, company, location)
 * - /stats endpoint (detailed stats)
 * - /contact?id= -> returns contact info
 * - /download?id= or /download?batch= -> returns alumni (Title-case keys)
 * - /add (POST single alumni)
 * - /add-bulk (POST multiple alumni)
 * - All JSON responses use Title-case keys for frontend consistency
 * - Proper CORS + OPTIONS handling
 */
public class Server {

    // Alumni data model (internal field names kept camelCase)
    static class Alumni {
        int id;
        String name;
        String department;
        int year;
        String email;
        String phone;
        String address;
        String job;
        String company;
        double cgpa;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "5050"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        System.out.println("✅ Backend Server running on port " + port);

        // Root health check
        server.createContext("/", exchange -> {
            handleCORS(exchange);
            if (isPreflight(exchange)) return;
            if ("GET".equals(exchange.getRequestMethod())) {
                String response = "Alumni Search Engine Backend Active 🚀";
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            }
        });

        // SEARCH - supports multiple filters and partial matching
        server.createContext("/search", exchange -> {
            handleCORS(exchange);
            if (isPreflight(exchange)) return;

            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());

                String idParam = params.getOrDefault("id", "").trim();
                String name = params.getOrDefault("name", "").toLowerCase().trim();
                String dept = params.getOrDefault("department", "").toLowerCase().trim();
                String yearParam = params.getOrDefault("year", "").trim();
                String location = params.getOrDefault("location", "").toLowerCase().trim();
                String company = params.getOrDefault("company", "").toLowerCase().trim();

                Alumni[] alumniList = readDatabase();
                List<Map<String, Object>> results = new ArrayList<>();

                for (Alumni a : alumniList) {
                    if (a == null || a.id == 0) continue; // skip invalid entries
                    boolean match = true;

                    if (!idParam.isEmpty()) {
                        try {
                            int qid = Integer.parseInt(idParam);
                            if (a.id != qid) match = false;
                        } catch (NumberFormatException e) {
                            match = false;
                        }
                    }
                    if (!name.isEmpty() && (a.name == null || !a.name.toLowerCase().contains(name))) match = false;
                    if (!dept.isEmpty() && (a.department == null || !a.department.toLowerCase().contains(dept))) match = false;
                    if (!yearParam.isEmpty()) {
                        try {
                            int qy = Integer.parseInt(yearParam);
                            if (a.year != qy) match = false;
                        } catch (NumberFormatException e) {
                            match = false;
                        }
                    }
                    if (!location.isEmpty() && (a.address == null || !a.address.toLowerCase().contains(location))) match = false;
                    if (!company.isEmpty() && (a.company == null || !a.company.toLowerCase().contains(company))) match = false;

                    if (match) {
                        results.add(formatAlumni(a));
                    }
                }

                sendJson(exchange, results);
            }
        });

        // CONTACT - return minimal contact info for given ID
        server.createContext("/contact", exchange -> {
            handleCORS(exchange);
            if (isPreflight(exchange)) return;

            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
                String idParam = params.get("id");

                if (idParam == null || idParam.trim().isEmpty()) {
                    sendJson(exchange, Collections.singletonMap("Error", "Missing ID parameter"));
                    return;
                }
                try {
                    int id = Integer.parseInt(idParam.trim());
                    Alumni[] alumniList = readDatabase();
                    for (Alumni a : alumniList) {
                        if (a.id == id) {
                            Map<String, Object> contact = new LinkedHashMap<>();
                            contact.put("ID", a.id);
                            contact.put("Name", a.name);
                            contact.put("Email", a.email != null ? a.email : "");
                            contact.put("Phone", a.phone != null ? a.phone : "");
                            sendJson(exchange, contact);
                            return;
                        }
                    }
                    sendJson(exchange, Collections.singletonMap("Error", "Alumni not found"));
                } catch (NumberFormatException e) {
                    sendJson(exchange, Collections.singletonMap("Error", "Invalid ID"));
                }
            }
        });

        // STATS - aggregated statistics for Home page
        server.createContext("/stats", exchange -> {
            handleCORS(exchange);
            if (isPreflight(exchange)) return;

            if ("GET".equals(exchange.getRequestMethod())) {
                Alumni[] alumniList = readDatabase();
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("Total Alumni", alumniList.length);

                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                int recentBatch = 0, currentBatch = 0;
                Map<String, Integer> deptCounts = new HashMap<>();
                Map<String, Integer> companyCounts = new HashMap<>();
                Map<String, Integer> locationCounts = new HashMap<>();

                for (Alumni a : alumniList) {
                    if (a != null) {
                        if (a.year == currentYear - 1) recentBatch++;
                        if (a.year == currentYear) currentBatch++;

                        String deptKey = a.department != null ? a.department : "Unknown";
                        deptCounts.put(deptKey, deptCounts.getOrDefault(deptKey, 0) + 1);

                        String compKey = a.company != null && !a.company.isEmpty() ? a.company : "Unknown";
                        companyCounts.put(compKey, companyCounts.getOrDefault(compKey, 0) + 1);

                        String locKey = a.address != null && !a.address.isEmpty() ? a.address : "Unknown";
                        locationCounts.put(locKey, locationCounts.getOrDefault(locKey, 0) + 1);
                    }
                }

                stats.put("Recent Batch", recentBatch);
                stats.put("Current Batch", currentBatch);
                stats.put("Departments", deptCounts.keySet().size());
                stats.put("Department Counts", sortByValueDesc(deptCounts));
                stats.put("Companies", companyCounts.keySet().size());
                stats.put("Company Counts", sortByValueDesc(companyCounts));
                stats.put("Locations", locationCounts.keySet().size());
                stats.put("Location Counts", sortByValueDesc(locationCounts));

                sendJson(exchange, stats);
            }
        });

        // ADD single alumni via POST /add
        server.createContext("/add", exchange -> {
            handleCORS(exchange);
            if (isPreflight(exchange)) return;

            if ("POST".equals(exchange.getRequestMethod())) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    Gson gson = new Gson();
                    Alumni newAlumni = gson.fromJson(reader, Alumni.class);
                    if (newAlumni == null) {
                        sendJson(exchange, Collections.singletonMap("Status", "Error"));
                        return;
                    }
                    Alumni[] alumniList = readDatabase();
                    List<Alumni> list = new ArrayList<>(Arrays.asList(alumniList));
                    int nextId = list.size() + 1;
                    newAlumni.id = nextId;
                    list.add(newAlumni);
                    writeDatabase(list.toArray(new Alumni[0]));
                    sendJson(exchange, Collections.singletonMap("Status", "Success"));
                } catch (Exception e) {
                    e.printStackTrace();
                    sendJson(exchange, Collections.singletonMap("Status", "Error"));
                }
            }
        });

        // ADD BULK (existing) via POST /add-bulk
        server.createContext("/add-bulk", exchange -> {
            handleCORS(exchange);
            if (isPreflight(exchange)) return;

            if ("POST".equals(exchange.getRequestMethod())) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    Gson gson = new Gson();
                    Alumni[] newAlumniArray = gson.fromJson(reader, Alumni[].class);
                    if (newAlumniArray == null) {
                        sendJson(exchange, Collections.singletonMap("Status", "Error"));
                        return;
                    }
                    Alumni[] alumniList = readDatabase();
                    List<Alumni> list = new ArrayList<>(Arrays.asList(alumniList));
                    int nextId = list.size() + 1;
                    for (Alumni a : newAlumniArray) {
                        a.id = nextId++;
                        list.add(a);
                    }
                    writeDatabase(list.toArray(new Alumni[0]));
                    sendJson(exchange, Collections.singletonMap("Status", "Success"));
                } catch (Exception e) {
                    e.printStackTrace();
                    sendJson(exchange, Collections.singletonMap("Status", "Error"));
                }
            }
        });

        // DOWNLOAD - by id or by batch/year
        server.createContext("/download", exchange -> {
            handleCORS(exchange);
            if (isPreflight(exchange)) return;

            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
                String idParam = params.get("id");
                String batchParam = params.get("batch");

                Alumni[] alumniList = readDatabase();
                List<Map<String, Object>> result = new ArrayList<>();

                if (idParam != null && !idParam.trim().isEmpty()) {
                    try {
                        int id = Integer.parseInt(idParam.trim());
                        for (Alumni a : alumniList) {
                            if (a.id == id) result.add(formatAlumni(a));
                        }
                    } catch (NumberFormatException e) {
                        // ignore invalid id - return empty
                    }
                } else if (batchParam != null && !batchParam.trim().isEmpty()) {
                    try {
                        int batch = Integer.parseInt(batchParam.trim());
                        for (Alumni a : alumniList) {
                            if (a.year == batch) result.add(formatAlumni(a));
                        }
                    } catch (NumberFormatException e) {
                        // ignore invalid batch
                    }
                }

                sendJson(exchange, result);
            }
        });

        server.start();
    }

    // -------------------- HELPERS --------------------

    // Capitalized keys like in Database.json
    private static Map<String, Object> formatAlumni(Alumni a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ID", a.id);
        m.put("Name", a.name);
        m.put("Department", a.department);
        m.put("Year", a.year);
        m.put("Email", a.email);
        m.put("Phone", a.phone);
        m.put("Address", a.address);
        m.put("Job", a.job);
        m.put("Company", a.company);
        m.put("CGPA", a.cgpa);
        return m;
    }

    private static boolean isPreflight(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            handleCORS(exchange);
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static void handleCORS(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Content-Type", "application/json; charset=UTF-8");
    }

    // ensure Database.json capitalization mapping
    private static Alumni[] readDatabase() throws IOException {
        File file = new File("Database.json");
        if (!file.exists()) return new Alumni[0];

        try (Reader reader = new FileReader(file)) {
            JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();
            List<Alumni> list = new ArrayList<>();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                Alumni a = new Alumni();
                a.id = obj.has("ID") ? obj.get("ID").getAsInt() : 0;
                a.name = obj.has("Name") ? obj.get("Name").getAsString() : "";
                a.department = obj.has("Department") ? obj.get("Department").getAsString() : "";
                a.year = obj.has("Year") ? obj.get("Year").getAsInt() : 0;
                a.email = obj.has("Email") ? obj.get("Email").getAsString() : "";
                a.phone = obj.has("Phone") ? obj.get("Phone").getAsString() : "";
                a.address = obj.has("Address") ? obj.get("Address").getAsString() : "";
                a.job = obj.has("Job") ? obj.get("Job").getAsString() : "";
                a.company = obj.has("Company") ? obj.get("Company").getAsString() : "";
                a.cgpa = obj.has("CGPA") ? obj.get("CGPA").getAsDouble() : 0.0;
                list.add(a);
            }
            return list.toArray(new Alumni[0]);
        }
    }

    private static void writeDatabase(Alumni[] alumniList) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer writer = new FileWriter("Database.json")) {
            gson.toJson(alumniList, writer);
        }
    }

    private static void sendJson(HttpExchange exchange, Object obj) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String response = gson.toJson(obj);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) return result;
        String[] pairs = query.split("&");
        for (String param : pairs) {
            String[] entry = param.split("=", 2);
            String key = entry[0];
            String value = entry.length > 1 ? entry[1] : "";
            try {
                value = URLDecoder.decode(value, StandardCharsets.UTF_8.toString());
            } catch (UnsupportedEncodingException e) {
            }
            result.put(key, value);
        }
        return result;
    }

    private static LinkedHashMap<String, Integer> sortByValueDesc(Map<String, Integer> map) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : list) result.put(e.getKey(), e.getValue());
        return result;
    }
}
