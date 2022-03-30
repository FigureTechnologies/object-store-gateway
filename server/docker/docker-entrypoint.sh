#!/bin/sh
set -e

java $JAVA_OPTS -jar $1 --spring.profiles.active="$SPRING_PROFILES_ACTIVE"
