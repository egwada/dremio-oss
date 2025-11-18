#!/bin/bash
# Script to generate protobuf files and check what methods are available

cd /home/user/dremio-oss/services/acl

echo "Generating protobuf files..."
mvn clean generate-sources -Ddremio.oss-only=true -q

if [ -f "target/generated-sources/protostuff/com/dremio/service/acl/proto/PrivilegeGrant.java" ]; then
    echo "✓ PrivilegeGrant.java generated"
    echo ""
    echo "Checking for builder methods..."
    grep -n "newBuilder\|toBuilder\|class Builder" target/generated-sources/protostuff/com/dremio/service/acl/proto/PrivilegeGrant.java | head -20
else
    echo "✗ PrivilegeGrant.java NOT generated"
    echo "Listing what was generated:"
    ls -la target/generated-sources/protostuff/com/dremio/service/acl/proto/ 2>/dev/null || echo "Directory doesn't exist"
fi
