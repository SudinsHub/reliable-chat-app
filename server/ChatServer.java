import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.sql.*;
import org.json.*;

public class ChatServer {
    private static final int PORT = 8080;
    private static final int RECEIVE_WINDOW_SIZE = 5;
    private static final double PACKET_LOSS_PROBABILITY = 0.1; // 10% packet loss simulation
    
    // Client state management
    private static final Map<String, ClientState> clientStates = new ConcurrentHashMap<>();
    private static final Map<String, Queue<Message>> messageQueues = new ConcurrentHashMap<>();
    private static final Random random = new Random();
    
    static class ClientState {
        int expectedSeqNum = 0;
        Queue<Message> receiveBuffer = new LinkedList<>();
        Set<Integer> confirmedAcks = new HashSet<>();
        long lastActivity = System.currentTimeMillis();
    }
    
    static class Message {
        String sender;
        String receiver;
        int seq;
        String content;
        String type; // "text" or "file_chunk"
        String fileName;
        int chunkIndex;
        int totalChunks;
        long timestamp;
        
        Message(String sender, String receiver, int seq, String content, String type) {
            this.sender = sender;
            this.receiver = receiver;
            this.seq = seq;
            this.content = content;
            this.type = type;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    public static void main(String[] args) throws Exception {
        // Initialize database
        DatabaseManager.initializeDatabase();
        
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        
        // CORS handler
        server.createContext("/", new CorsHandler());
        server.createContext("/send-message", new SendMessageHandler());
        server.createContext("/receive", new ReceiveHandler());
        server.createContext("/upload-chunk", new FileUploadHandler());
        server.createContext("/download-file", new FileDownloadHandler());
        server.createContext("/users", new UsersHandler());
        
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        
        System.out.println("Chat server started on port " + PORT);
        
        // Start cleanup task
        startCleanupTask();
    }
    
    static class CorsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        }
    }
    
    static class SendMessageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }
            
            try {
                String requestBody = readRequestBody(exchange);
                JSONObject json = new JSONObject(requestBody);
                
                String sender = json.getString("sender");
                String receiver = json.getString("receiver");
                int seq = json.getInt("seq");
                String content = json.getString("message");
                String type = json.optString("type", "text");
                
                // Simulate packet loss
                if (random.nextDouble() < PACKET_LOSS_PROBABILITY) {
                    System.out.println("Simulating packet loss for seq: " + seq);
                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                    return;
                }
                
                ClientState clientState = clientStates.computeIfAbsent(receiver, k -> new ClientState());
                
                JSONObject response = new JSONObject();
                
                if (seq == clientState.expectedSeqNum) {
                    // Expected sequence number - accept message
                    if (clientState.receiveBuffer.size() < RECEIVE_WINDOW_SIZE) {
                        Message message = new Message(sender, receiver, seq, content, type);
                        clientState.receiveBuffer.offer(message);
                        
                        // Store in database
                        DatabaseManager.storeMessage(message);
                        
                        // Add to message queue for receiver
                        messageQueues.computeIfAbsent(receiver, k -> new ConcurrentLinkedQueue<>()).offer(message);
                        
                        clientState.expectedSeqNum++;
                        clientState.confirmedAcks.add(seq);
                        
                        response.put("ack", seq);
                        System.out.println("Accepted message seq: " + seq + " from " + sender + " to " + receiver);
                    } else {
                        // Flow control - buffer full
                        response.put("ack", clientState.expectedSeqNum - 1);
                        System.out.println("Buffer full, rejecting seq: " + seq);
                    }
                } else if (seq < clientState.expectedSeqNum) {
                    // Duplicate or old packet
                    response.put("ack", clientState.expectedSeqNum - 1);
                    System.out.println("Duplicate packet seq: " + seq);
                } else {
                    // Future packet - out of order
                    response.put("ack", clientState.expectedSeqNum - 1);
                    System.out.println("Out of order packet seq: " + seq);
                }
                
                clientState.lastActivity = System.currentTimeMillis();
                
                String responseStr = response.toString();
                exchange.sendResponseHeaders(200, responseStr.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseStr.getBytes());
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, 0);
            } finally {
                exchange.close();
            }
        }
    }
    
    static class ReceiveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            
            try {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQuery(query);
                
                String user = params.get("user");
                int lastAck = Integer.parseInt(params.getOrDefault("lastAck", "-1"));
                
                Queue<Message> userMessages = messageQueues.get(user);
                JSONArray messages = new JSONArray();
                
                if (userMessages != null) {
                    Iterator<Message> iterator = userMessages.iterator();
                    while (iterator.hasNext()) {
                        Message msg = iterator.next();
                        if (msg.seq > lastAck) {
                            JSONObject msgJson = new JSONObject();
                            msgJson.put("sender", msg.sender);
                            msgJson.put("seq", msg.seq);
                            msgJson.put("message", msg.content);
                            msgJson.put("type", msg.type);
                            msgJson.put("timestamp", msg.timestamp);
                            if (msg.fileName != null) {
                                msgJson.put("fileName", msg.fileName);
                                msgJson.put("chunkIndex", msg.chunkIndex);
                                msgJson.put("totalChunks", msg.totalChunks);
                            }
                            messages.put(msgJson);
                        }
                    }
                }
                
                ClientState clientState = clientStates.get(user);
                int currentAck = clientState != null ? clientState.expectedSeqNum - 1 : -1;
                
                JSONObject response = new JSONObject();
                response.put("messages", messages);
                response.put("ack", currentAck);
                
                String responseStr = response.toString();
                exchange.sendResponseHeaders(200, responseStr.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseStr.getBytes());
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, 0);
            } finally {
                exchange.close();
            }
        }
    }
    
    static class FileUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            
            try {
                String requestBody = readRequestBody(exchange);
                JSONObject json = new JSONObject(requestBody);
                
                String sender = json.getString("sender");
                String receiver = json.getString("receiver");
                String fileName = json.getString("fileName");
                String chunkData = json.getString("chunkData");
                int chunkIndex = json.getInt("chunkIndex");
                int totalChunks = json.getInt("totalChunks");
                int seq = json.getInt("seq");
                
                // Store file chunk
                DatabaseManager.storeFileChunk(sender, receiver, fileName, chunkIndex, totalChunks, chunkData);
                
                // Create message for file chunk
                Message message = new Message(sender, receiver, seq, "File chunk: " + fileName, "file_chunk");
                message.fileName = fileName;
                message.chunkIndex = chunkIndex;
                message.totalChunks = totalChunks;
                
                messageQueues.computeIfAbsent(receiver, k -> new ConcurrentLinkedQueue<>()).offer(message);
                
                JSONObject response = new JSONObject();
                response.put("ack", seq);
                response.put("status", "chunk_received");
                
                String responseStr = response.toString();
                exchange.sendResponseHeaders(200, responseStr.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseStr.getBytes());
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, 0);
            } finally {
                exchange.close();
            }
        }
    }
    
    static class FileDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            
            try {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQuery(query);
                
                String fileName = params.get("fileName");
                String receiver = params.get("receiver");
                
                List<String> chunks = DatabaseManager.getFileChunks(fileName, receiver);
                
                JSONObject response = new JSONObject();
                response.put("fileName", fileName);
                response.put("chunks", new JSONArray(chunks));
                
                String responseStr = response.toString();
                exchange.sendResponseHeaders(200, responseStr.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseStr.getBytes());
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, 0);
            } finally {
                exchange.close();
            }
        }
    }
    
    static class UsersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            
            try {
                List<String> users = DatabaseManager.getActiveUsers();
                JSONArray response = new JSONArray(users);
                
                String responseStr = response.toString();
                exchange.sendResponseHeaders(200, responseStr.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseStr.getBytes());
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, 0);
            } finally {
                exchange.close();
            }
        }
    }
    
    private static void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
    }
    
    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody()))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        }
    }
    
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    params.put(keyValue[0], keyValue[1]);
                }
            }
        }
        return params;
    }
    
    private static void startCleanupTask() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            clientStates.entrySet().removeIf(entry -> 
                currentTime - entry.getValue().lastActivity > 300000); // 5 minutes timeout
        }, 60, 60, TimeUnit.SECONDS);
    }
}
