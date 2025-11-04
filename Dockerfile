# Build with Maven + JDK 21
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Leverage Docker cache by copying pom.xml separately
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Now copy the source code
COPY src ./src

# Build the JAR (skip tests for speed)
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre

WORKDIR /app

# Install CA certificates and sync with Java truststore
RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates && \
    update-ca-certificates && \
    # Import all system CA certificates into Java's truststore
    for cert in /etc/ssl/certs/*.pem; do \
        [ -f "$cert" ] || continue; \
        alias=$(basename "$cert" .pem); \
        keytool -importcert -noprompt \
            -keystore $JAVA_HOME/lib/security/cacerts \
            -storepass changeit \
            -alias "$alias" \
            -file "$cert" 2>/dev/null || true; \
    done && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Poseidon
LABEL internal-port="8080"
LABEL phoenix.source="phoenix.mia.ws"
LABEL phoenix.self="true"

# Copy the built JAR from build stage
COPY --from=build /app/target/*.jar ./app.jar

EXPOSE 8080

# Run
ENTRYPOINT ["java", "-jar", "app.jar"]