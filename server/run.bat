@echo off

if not exist lib\sqlite-jdbc.jar (
    echo Dependencies not found. Running compile script...
    call compile.bat
)

echo Starting Chat Server...
java -cp "bin;lib/*" ChatServer
