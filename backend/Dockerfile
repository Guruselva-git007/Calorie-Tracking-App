FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY target/calorie-tracker-backend-*.jar /app/app.jar

EXPOSE 8080

ENV SERVER_ADDRESS=0.0.0.0
ENTRYPOINT ["sh", "-c", "java -jar /app/app.jar"]
