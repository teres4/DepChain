FROM maven:3.9.9-eclipse-temurin-21

WORKDIR /app

# 1. Dependencies (cached layer - only invalidated when pom.xml changes)
COPY pom.xml .
RUN mvn -q -e dependency:go-offline

# 2. Source + config (invalidated when any source file changes)
COPY src ./src
COPY config ./config

# 3. Compile main + test sources at build time
RUN mvn -q compile test-compile -DskipTests

CMD ["tail", "-f", "/dev/null"]