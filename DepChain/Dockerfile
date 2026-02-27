# ---------- BUILD STAGE ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom first (better caching)
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline

# Copy source
COPY src ./src

# Compile once (optional but speeds up runtime)
RUN mvn -q -DskipTests clean compile


# ---------- RUNTIME STAGE ----------
FROM maven:3.9.9-eclipse-temurin-21

WORKDIR /app

# Copy project (code + config)
COPY . .

# Download dependencies once
RUN mvn -q -e -DskipTests dependency:go-offline

# Keep container alive
CMD ["tail", "-f", "/dev/null"]