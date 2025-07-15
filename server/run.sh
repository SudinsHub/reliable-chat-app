#!/bin/bash

# Make sure we have the dependencies
if [ ! -f "lib/sqlite-jdbc.jar" ] || [ ! -f "lib/json.jar" ]; then
    echo "Dependencies not found. Running compile script..."
    chmod +x compile.sh
    ./compile.sh
fi

# Run the server
echo "Starting Chat Server..."
java -cp ".:lib/*" ChatServer
