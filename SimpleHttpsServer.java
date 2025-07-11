import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAress;
import java.security.KeyStore;
import java.util.*;

public class SimpleHttpsServer {
    private static final String SENSOR_FILE_PATH = "/path/to/sensors.txt";

    public static void main(String[] args) throws Exception {
        // --- Setup SSL context ---
        char[] password = "password".toCharArray(); // matches your keytool setup
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream("testkeystore.jks")) {
            ks.load(fis, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, password);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);

        // --- Start HTTPS server ---
        HttpsServer server = HttpsServer.create(new InetSocketAddress(8443), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));

        server.createContext("/sensors", exchange -> {
            // Only allow GET
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // Parse query params
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
            String latStr = params.get("lat");
            String lngStr = params.get("lng");
            String radiiStr = params.get("radii");

            String responseText = "";
            int responseCode = 200;

            if (latStr == null || lngStr == null || radiiStr == null) {
                responseCode = 400;
                responseText = "Missing parameter";
            } else {
                double centerLat = 0, centerLng = 0, radii = 0;
                try {
                    centerLat = Double.parseDouble(latStr);
                    centerLng = Double.parseDouble(lngStr);
                    radii = Double.parseDouble(radiiStr);
                } catch (NumberFormatException e) {
                    responseCode = 400;
                    responseText = "Invalid parameter format";
                }

                if (responseCode == 200) {
                    ArrayList<String> outArr = new ArrayList<>();
                    try (BufferedReader reader = new BufferedReader(new FileReader(SENSOR_FILE_PATH))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String[] parts = line.split("/");
                            if (parts.length != 4) continue;
                            String id = parts[0];
                            double lat, lng;
                            try {
                                lat = Double.parseDouble(parts[1]);
                                lng = Double.parseDouble(parts[2]);
                            } catch (NumberFormatException e) {
                                continue;
                            }
                            if (Math.abs(lat - centerLat) <= radii && Math.abs(lng - centerLng) <= radii) {
                                boolean override = false;
                                for (int i = 0; i < outArr.size(); i++) {
                                    if (outArr.get(i).startsWith(id + "/")) {
                                        outArr.set(i, line);
                                        override = true;
                                        break;
                                    }
                                }
                                if (!override) outArr.add(line);
                            }
                        }
                        // Join output lines with newline (as in your servlet)
                        StringBuilder sb = new StringBuilder();
                        for (String l : outArr) sb.append(l).append("\n");
                        responseText = sb.toString();
                    } catch (IOException e) {
                        responseCode = 500;
                        responseText = "Server error: " + e.getMessage();
                    }
                }
            }

            // Write response
            responseText.concat("cia");
            byte[] resp = responseText.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(responseCode, resp.length);
            OutputStream os = exchange.getResponseBody();
            os.write(resp);
            os.close();
        });

        server.start();
        System.out.println("HTTPS sensor server running at https://localhost:8443/sensors");
    }

    // Helper function to parse query parameters
    public static Map<String, String> queryToMap(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) map.put(pair[0], pair[1]);
        }
        return map;
    }
}
