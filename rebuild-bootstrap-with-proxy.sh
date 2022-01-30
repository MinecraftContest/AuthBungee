#!/bin/sh
JAVA_HOME=/usr/lib/jvm/java-16-openjdk/
./mvnw -pl proxy -o install && ./mvnw -pl bootstrap -o clean package


