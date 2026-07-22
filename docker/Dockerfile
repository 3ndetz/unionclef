# ╔══════════════════════════════════════════════════════════╗
# ║  ClefForge — unionclef build container                   ║
# ║  JDK 21 · Gradle 8.9 · Fabric Loom · 8G heap            ║
# ╚══════════════════════════════════════════════════════════╝

FROM ubuntu:jammy AS clefforge-base

ENV DEBIAN_FRONTEND=noninteractive \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 \
    GRADLE_OPTS="-Xmx8192M -Dorg.gradle.daemon=false" \
    MC_VERSION=1.21.1

RUN apt-get update -y && apt-get install -y --no-install-recommends \
    openjdk-21-jdk \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /forge

# ── Gradle cache preheat (layer cache) ─────────────────────
COPY gradlew gradlew.bat settings.gradle.kts build.gradle \
     root.gradle.kts gradle.properties versions ./
COPY gradle/ gradle/

RUN ./gradlew --version --no-daemon

# ── Full project → build ───────────────────────────────────
COPY . /forge

RUN ./gradlew build --no-daemon --stacktrace \
    && mkdir -p /out \
    && cp versions/${MC_VERSION}/build/libs/*.jar /out/


# ═══════════════════════════════════════════════════════════
#  Runtime — serve artifacts, dev loop, healthcheck
# ═══════════════════════════════════════════════════════════
FROM ubuntu:jammy AS clefforge

RUN apt-get update -y && apt-get install -y --no-install-recommends \
    openjdk-21-jdk-headless \
    curl \
    python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /forge

COPY --from=clefforge-base /out /forge/artifacts/
COPY docker/server.py /forge/server.py

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

EXPOSE 8080

VOLUME ["/forge/artifacts", "/forge/src"]

ENTRYPOINT ["python3", "/forge/server.py"]
