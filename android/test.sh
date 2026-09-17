#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mkdir -p build/tests
"$JAVA_HOME/bin/javac" --release 11 -d build/tests \
  app/src/com/rfid/helper/RfidProto.java \
  app/src/com/rfid/helper/TagMemoryReader.java \
  app/src/com/rfid/helper/TagWriter.java \
  app/src/com/rfid/helper/TagClipboard.java \
  tests/com/rfid/helper/TagMemoryReaderTest.java \
  tests/com/rfid/helper/TagWriterTest.java \
  tests/com/rfid/helper/TagClipboardTest.java
"$JAVA_HOME/bin/java" -cp build/tests com.rfid.helper.TagMemoryReaderTest
"$JAVA_HOME/bin/java" -cp build/tests com.rfid.helper.TagWriterTest
"$JAVA_HOME/bin/java" -cp build/tests com.rfid.helper.TagClipboardTest
