FROM openjdk:8-jre

VOLUME ["/hygieia/logs"]

RUN mkdir -p /hygieia/config

EXPOSE 8080

ENV PROP_FILE /hygieia/config/application.properties
ENV PROJ_JAR api.jar

WORKDIR /hygieia

COPY target/api.jar /hygieia/
COPY docker/properties-builder.sh /hygieia/

CMD ./properties-builder.sh &&\
  java -Djava.security.egd=file:/dev/./urandom -jar $PROJ_JAR --spring.config.location=$PROP_FILE
