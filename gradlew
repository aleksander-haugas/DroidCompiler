#!/usr/bin/env sh
if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
fi
printf '%s\n' "gradle-wrapper.jar is not bundled in this starter ZIP." 
printf '%s\n' "Open the project in Android Studio, or run: gradle wrapper"
exit 1
