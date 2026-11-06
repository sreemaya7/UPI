
FROM eclipse-temurin:17-jdk

WORKDIR /app

# Download database and logging libraries
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && curl -fL https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.45.1.0/sqlite-jdbc-3.45.1.0.jar -o sqlite-jdbc-3.45.1.0.jar \
    && curl -fL https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar -o slf4j-api-2.0.9.jar \
    && curl -fL https://repo.maven.apache.org/maven2/org/slf4j/slf4j-simple/2.0.9/slf4j-simple-2.0.9.jar -o slf4j-simple-2.0.9.jar \
    && curl -fL https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar -o postgresql-42.7.5.jar \
    && rm -rf /var/lib/apt/lists/*

# Copy Java source files
COPY *.java ./

# Copy frontend
COPY index.html ./index.html

# Compile application
RUN mkdir -p out data web \
    && cp index.html web/index.html \
    && javac -d out -cp "sqlite-jdbc-3.45.1.0.jar:slf4j-api-2.0.9.jar:slf4j-simple-2.0.9.jar:postgresql-42.7.5.jar" *.java \
    && useradd --create-home app \
    && chown -R app:app /app

USER app

ENV DB_PATH=/app/data/upi.db

EXPOSE 8080

CMD ["java", "-XX:MaxRAMPercentage=70", "-cp", "out:sqlite-jdbc-3.45.1.0.jar:slf4j-api-2.0.9.jar:slf4j-simple-2.0.9.jar:postgresql-42.7.5.jar", "Main"]
