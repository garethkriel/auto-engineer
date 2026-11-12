#!/usr/bin/env bash
# Optional setup script for the "AI model" cloud environment. Paste it into the
# environment's Setup script field at claude.ai/code. It warms the Maven cache
# with the template pom's dependencies so each run skips that download. It must
# exit 0 and finish within about 5 minutes to be cached, so every step is
# best-effort.
set -u
work=$(mktemp -d)
cat > "$work/pom.xml" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>ae</groupId>
  <artifactId>warmup</artifactId>
  <version>1.0</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.11.4</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.2</version>
      </plugin>
    </plugins>
  </build>
</project>
POM
mkdir -p "$work/src/test/java"
printf 'class WarmupTest { @org.junit.jupiter.api.Test void ok() {} }\n' > "$work/src/test/java/WarmupTest.java"
(cd "$work" && timeout 240 mvn -q -B test) || true
rm -rf "$work"
exit 0
