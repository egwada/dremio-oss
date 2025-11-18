#!/bin/bash
# Script to run ACL module unit tests

set -e

echo "========================================="
echo "Running ACL Module Unit Tests"
echo "========================================="

cd "$(dirname "$0")"

echo ""
echo "Step 1: Compile test classes..."
mvn test-compile -Ddremio.oss-only=true

echo ""
echo "Step 2: Run unit tests..."
mvn test -Ddremio.oss-only=true

echo ""
echo "========================================="
echo "Tests completed successfully!"
echo "========================================="
