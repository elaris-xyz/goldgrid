# Source from Git Bash before Gradle: everything Android lives on E: (C: is full).
# Paths handed to Java must be Windows-style (E:/...), not Git Bash's /e/...
export JAVA_HOME="E:/Android/jdk-17.0.20.1+1"
export ANDROID_HOME="E:/Android/sdk"
export ANDROID_USER_HOME="E:/Android/.android"
export GRADLE_USER_HOME="E:/Android/gradle-home"
export PATH="/e/Android/jdk-17.0.20.1+1/bin:/e/Android/gradle-9.8.0/bin:/e/Android/sdk/platform-tools:$PATH"
