#!/bin/bash
# shop-copilot 本地环境:JDK 17
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
echo "✅ shop-copilot env loaded: JAVA_HOME=$JAVA_HOME"
