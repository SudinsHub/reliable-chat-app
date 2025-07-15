import java.sql.*;
import java.util.*;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:chat.db";
    
    public static void initializeDatabase() {
        try {
            // Load the SQLite JDBC driver
            Class.forName("org.sqlite.JDBC");

            // Connect to the SQLite database
            Connection conn = DriverManager.getConnection(DB_URL);

            // SQL to create messages table
            String createMessagesTable = "CREATE TABLE IF NOT EXISTS messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender TEXT NOT NULL, " +
                "receiver TEXT NOT NULL, " +
                "seq INTEGER NOT NULL, " +
                "content TEXT NOT NULL, " +
                "type TEXT DEFAULT 'text', " +
                "timestamp INTEGER NOT NULL" +
                ")";

            // SQL to create file_chunks table
            String createFileChunksTable = "CREATE TABLE IF NOT EXISTS file_chunks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender TEXT NOT NULL, " +
                "receiver TEXT NOT NULL, " +
                "file_name TEXT NOT NULL, " +
                "chunk_index INTEGER NOT NULL, " +
                "total_chunks INTEGER NOT NULL, " +
                "chunk_data TEXT NOT NULL, " +
                "timestamp INTEGER NOT NULL" +
                ")";

            // SQL to create users table
            String createUsersTable = "CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "username TEXT UNIQUE NOT NULL, " +
                "last_activity INTEGER NOT NULL" +
                ")";

            // Execute all create table statements
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createMessagesTable);
                stmt.execute(createFileChunksTable);
                stmt.execute(createUsersTable);
            }

            conn.close();
            System.out.println("✅ Database initialized successfully");

        } catch (ClassNotFoundException e) {
            System.err.println("❌ SQLite JDBC driver not found.");
            e.printStackTrace();
        } catch (SQLException e) {
            System.err.println("❌ Database error:");
            e.printStackTrace();
        }
    }

    
    public static void storeMessage(ChatServer.Message message) {
        String sql = "INSERT INTO messages (sender, receiver, seq, content, type, timestamp) VALUES (?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, message.sender);
            pstmt.setString(2, message.receiver);
            pstmt.setInt(3, message.seq);
            pstmt.setString(4, message.content);
            pstmt.setString(5, message.type);
            pstmt.setLong(6, message.timestamp);
            
            pstmt.executeUpdate();
            
            // Update user activity
            updateUserActivity(message.sender);
            updateUserActivity(message.receiver);
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    public static void storeFileChunk(String sender, String receiver, String fileName, 
                                    int chunkIndex, int totalChunks, String chunkData) {
        String sql = "INSERT INTO file_chunks (sender, receiver, file_name, chunk_index, total_chunks, chunk_data, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, sender);
            pstmt.setString(2, receiver);
            pstmt.setString(3, fileName);
            pstmt.setInt(4, chunkIndex);
            pstmt.setInt(5, totalChunks);
            pstmt.setString(6, chunkData);
            pstmt.setLong(7, System.currentTimeMillis());
            
            pstmt.executeUpdate();
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    public static List<String> getFileChunks(String fileName, String receiver) {
        String sql = "SELECT chunk_data FROM file_chunks WHERE file_name = ? AND receiver = ? ORDER BY chunk_index";
        List<String> chunks = new ArrayList<>();
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, fileName);
            pstmt.setString(2, receiver);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    chunks.add(rs.getString("chunk_data"));
                }
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
        
        return chunks;
    }
    
    public static List<String> getActiveUsers() {
        String sql = "SELECT DISTINCT username FROM users WHERE last_activity > ?";
        List<String> users = new ArrayList<>();
        long fiveMinutesAgo = System.currentTimeMillis() - 300000;
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setLong(1, fiveMinutesAgo);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    users.add(rs.getString("username"));
                }
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
        
        return users;
    }
    
    private static void updateUserActivity(String username) {
        String sql = "INSERT OR REPLACE INTO users (username, last_activity) VALUES (?, ?)";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            pstmt.setLong(2, System.currentTimeMillis());
            
            pstmt.executeUpdate();
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
