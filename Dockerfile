# SILLAGE — single-stage build on a JRE-sized runtime.
# The app is pure Java with no dependencies, so the image is just: compile, copy, run.
#
#   docker build -t sillage .
#   docker run --rm -p 8080:8080 -v sillage-data:/app/data sillage
#
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY build.sh ./
COPY src ./src
RUN ./build.sh

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/build/classes /app/classes
COPY web ./web
ENV SILLAGE_DATA=/app/data \
    SILLAGE_WEB=/app/web \
    SILLAGE_HOST=0.0.0.0 \
    SILLAGE_PORT=8080
EXPOSE 8080
VOLUME ["/app/data"]
HEALTHCHECK --interval=30s --timeout=4s --start-period=10s \
  CMD ["/opt/java/openjdk/bin/java", "-version"]
ENTRYPOINT ["/opt/java/openjdk/bin/java", "-cp", "/app/classes", "com.sillage.SillageApp"]
