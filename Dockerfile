FROM clojure:tools-deps AS builder

WORKDIR /

RUN apt-get update && apt-get install -y curl
RUN curl -sL https://raw.githubusercontent.com/lambdaisland/open-source/main/bin/install_babashka | sh -s -- /usr/bin

COPY . .
RUN bin/dev uberjar
CMD clojure -M -m casa.squid.filebak --upload-dir /uploads

FROM openjdk:17-slim-buster AS runtime
COPY --from=builder /filebak/target/filebak.jar /app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
