#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/jvm-tests
python3 -m unittest discover -s tests -p 'test_*.py' -v > artifacts/python-tests.txt 2>&1
cat artifacts/python-tests.txt
java --module jdk.compiler/com.sun.tools.javac.Main -d artifacts/jvm-tests \
  android/controller/src/main/java/org/phonebridge/controller/GuardPolicy.java tests/GuardPolicyTest.java
java -cp artifacts/jvm-tests GuardPolicyTest | tee artifacts/native-policy-tests.txt
java tests/ParseJava.java | tee artifacts/java-syntax.txt
python3 scripts/check-project.py
