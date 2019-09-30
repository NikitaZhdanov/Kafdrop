FROM maven:3.6-jdk-8-slim as build

COPY . /build

RUN cd /build && \
	mvn clean install -B

#################################################################

FROM openjdk:8-jre-alpine

RUN mkdir /opt/log

COPY --from=build /build/target/kafdrop.jar /opt/

WORKDIR /opt
EXPOSE 8080

ENTRYPOINT exec java $JAVA_OPTS -jar /opt/kafdrop.jar --kafka.connect=${ESU_KAFKA_URL} --zookeeper.connect=${ESU_ZOOKEEPER_URL} --logging.file=/opt/log/kafdrop.log