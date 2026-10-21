FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/*.jar app.jar

EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "app.jar"]