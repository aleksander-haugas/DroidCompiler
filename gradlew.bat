@echo off
if exist gradle\wrapper\gradle-wrapper.jar (
  java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain %*
) else (
  echo gradle-wrapper.jar is not bundled in this starter ZIP.
  echo Open the project in Android Studio, or run: gradle wrapper
  exit /b 1
)
