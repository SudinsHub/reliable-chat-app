#!/bin/bash

# Create lib directory for dependencies
mkdir -p lib

# Download required JAR files
echo "Downloading dependencies..."

# SQLite JDBC driver
curl -L "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.44.1.0/sqlite-jdbc-3.44.1.0.jar" -o lib/sqlite-jdbc.jar

# JSON library
curl -L "https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar" -o lib/json.jar

echo "Compiling Java files..."

# Compile with classpath
javac -cp ".:lib/*" *.java

echo "Compilation complete. Run with: java -cp '.:lib/*' ChatServer"
