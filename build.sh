#!/bin/sh
# Builds rmi-arena.jar (needs a JDK 17 or newer)
set -e
cd "$(dirname "$0")"
rm -rf out
javac --release 17 -d out src/arena/*.java src/arena/common/*.java src/arena/server/*.java src/arena/client/*.java src/arena/bot/*.java
jar --create --file rmi-arena.jar --main-class arena.Main -C out .
echo "Built rmi-arena.jar"
