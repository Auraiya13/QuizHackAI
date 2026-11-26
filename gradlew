#!/usr/bin/env bash
##############################################################################
# Gradle wrapper stub — downloads the real gradle-wrapper.jar on first run.
# Commit this file to Git so GitHub Actions can call ./gradlew
##############################################################################

set -euo pipefail

APP_NAME="Gradle"
GRADLE_WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
GRADLE_WRAPPER_PROPERTIES="gradle/wrapper/gradle-wrapper.properties"

# Download wrapper jar if missing
if [ ! -f "$GRADLE_WRAPPER_JAR" ]; then
    echo "Downloading Gradle Wrapper..."
    DIST_URL=$(grep 'distributionUrl' "$GRADLE_WRAPPER_PROPERTIES" | cut -d'=' -f2- | sed 's/\\//g' | tr -d '[:space:]')
    WRAPPER_VERSION=$(echo "$DIST_URL" | grep -oP 'gradle-\K[0-9.]+')
    WRAPPER_JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v${WRAPPER_VERSION}/gradle/wrapper/gradle-wrapper.jar"
    
    # Fallback: download from services.gradle.org
    mkdir -p "$(dirname "$GRADLE_WRAPPER_JAR")"
    curl -sL "https://services.gradle.org/distributions/gradle-${WRAPPER_VERSION}-wrapper.jar" -o "$GRADLE_WRAPPER_JAR" 2>/dev/null || \
    curl -sL "$WRAPPER_JAR_URL" -o "$GRADLE_WRAPPER_JAR" 2>/dev/null || {
        echo "Could not download gradle-wrapper.jar. Trying gradle init..."
        # Use system gradle if available
        if command -v gradle &>/dev/null; then
            gradle wrapper --gradle-version "$WRAPPER_VERSION"
        else
            echo "ERROR: Cannot bootstrap Gradle wrapper. Install gradle or download gradle-wrapper.jar manually."
            exit 1
        fi
    }
fi

# Determine JAVA_HOME
if [ -z "${JAVA_HOME:-}" ]; then
    if command -v java &>/dev/null; then
        JAVACMD="java"
    else
        echo "ERROR: JAVA_HOME is not set and java is not in PATH."
        exit 1
    fi
else
    JAVACMD="$JAVA_HOME/bin/java"
fi

exec "$JAVACMD" \
    ${JAVA_OPTS:-"-Xmx2048m"} \
    -classpath "$GRADLE_WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
