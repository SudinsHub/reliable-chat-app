#!/bin/bash

# Create directories
mkdir -p lib
mkdir -p bin

echo "Downloading dependencies..."

# SQLite JDBC driver
curl -L "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.44.1.0/sqlite-jdbc-3.44.1.0.jar" -o lib/sqlite-jdbc.jar

# JSON library
curl -L "https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar" -o lib/json.jar

# SLF4J libraries
curl -L "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar" -o lib/slf4j-api.jar
curl -L "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar" -o lib/slf4j-simple.jar

echo "Compiling Java files..."
javac -cp "lib/*" -d bin *.java

echo "Compilation complete. Run with: java -cp 'bin:lib/*' ChatServer"
