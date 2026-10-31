# Stage 1: Build the jar from source (no local JDK required on host)
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build
COPY src/ ./src/
RUN mkdir out && \
    javac --release 17 -d out src/arena/*.java src/arena/common/*.java src/arena/server/*.java src/arena/client/*.java src/arena/bot/*.java && \
    jar --create --file rmi-arena.jar --main-class arena.Main -C out .

# Stage 2: Runtime with Xvfb virtual display and noVNC web browser UI
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jre \
    xvfb \
    fluxbox \
    x11vnc \
    novnc \
    websockify \
    net-tools \
    procps \
    pulseaudio \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy built jar from builder stage
COPY --from=builder /build/rmi-arena.jar /app/rmi-arena.jar

# Copy launcher script
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 6080

ENTRYPOINT ["/app/entrypoint.sh"]
