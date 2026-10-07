FROM eclipse-temurin:25.0.4.1_1-jre

# iproute2 for ss, nghttp2-client to read the server's HTTP/2 SETTINGS, procps for ps.
RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 nghttp2-client procps curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY build/quarkus-app/lib/ /app/lib/
COPY build/quarkus-app/*.jar /app/
COPY build/quarkus-app/app/ /app/app/
COPY build/quarkus-app/quarkus/ /app/quarkus/

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
