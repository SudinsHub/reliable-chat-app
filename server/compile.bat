@echo off
echo Creating directories...
mkdir lib 2>nul
mkdir bin 2>nul

echo Downloading dependencies...

:: SQLite JDBC driver
curl -L -o lib\sqlite-jdbc.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.44.1.0/sqlite-jdbc-3.44.1.0.jar

:: JSON library
curl -L -o lib\json.jar https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar

:: SLF4J libraries
curl -L -o lib\slf4j-api.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
curl -L -o lib\slf4j-simple.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar

echo Compiling Java files...
javac -cp "lib/*" -d bin *.java

echo.
echo Compilation complete. Run with:
echo     run.bat
