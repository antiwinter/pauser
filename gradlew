#!/bin/sh
# Gradle wrapper script - minimal version
set -e
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Download gradle if needed
GRADLE_DIST="$GRADLE_HOME/wrapper/dists"
exec java -Xmx64m -Xms64m \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
