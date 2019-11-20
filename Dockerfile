FROM maven:3.6-jdk-8-slim as build

COPY . /build

RUN cd /build && \
	mvn clean install -B

#################################################################

FROM openjdk:8-jre-alpine

RUN mkdir /opt/log

COPY --from=build /build/target/kafdrop.jar /opt/

WORKDIR /opt
EXPOSE 9000

ENTRYPOINT exec java $JAVA_OPTS -jar /opt/kafdrop.jar --kafka.connect=${KAFDROP_ESU_KAFKA_URL} --zookeeper.connect=${KAFDROP_ESU_ZOOKEEPER_URL} --server.port=9000 --logging.file=/opt/log/kafdrop.log