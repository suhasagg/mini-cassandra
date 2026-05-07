FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./scripts/build.sh

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/mini-cassandra.jar /app/mini-cassandra.jar
EXPOSE 8080
ENV DATA_DIR=/app/data
CMD ["java", "-jar", "/app/mini-cassandra.jar"]
