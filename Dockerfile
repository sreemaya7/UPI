
FROM eclipse-temurin:17-jdk

WORKDIR /app

# Copy Java dependencies
COPY sqlite-jdbc-3.45.1.0.jar slf4j-api-2.0.9.jar slf4j-simple-2.0.9.jar ./

# Copy Java source files from the root folder
COPY *.java ./

# Copy frontend HTML
RUN mkdir -p out data web \
 && cp index.html web/index.html \
 && javac -d out -cp "sqlite-jdbc-3.45.1.0.jar:slf4j-api-2.0.9.jar:slf4j-simple-2.0.9.jar" *.java \
 && useradd --create-home app \
 && chown -R app:app /app

USER app

ENV DB_PATH=/app/data/upi.db

EXPOSE 8080

CMD ["java", "-XX:MaxRAMPercentage=70", "-cp", "out:sqlite-jdbc-3.45.1.0.jar:slf4j-api-2.0.9.jar:slf4j-simple-2.0.9.jar", "Main"]
