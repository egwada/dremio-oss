#!/bin/bash
set -e

echo "============================================"
echo "  Building Dremio with ACL Plugin"
echo "============================================"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. Build tout sauf ACL
echo -e "${YELLOW}Step 1/4:${NC} Building core modules (excluding ACL)..."
mvn clean install -DskipTests -pl '!services/acl,!services/pubsub-nats,!services/reindexer' || {
    echo -e "${RED}✗ Core build failed${NC}"
    exit 1
}
echo -e "${GREEN}✓ Core modules built successfully${NC}"
echo ""

# 2. Generate ACL protobuf classes
echo -e "${YELLOW}Step 2/4:${NC} Generating ACL protobuf classes..."
cd services/acl
mvn generate-sources || {
    echo -e "${RED}✗ Protobuf generation failed${NC}"
    exit 1
}

# Verify generation
if [ ! -f "target/generated-sources/protostuff/com/dremio/service/acl/proto/AclProtobuf.java" ]; then
    echo -e "${RED}✗ ERROR: Protobuf classes not generated!${NC}"
    echo "Expected file: target/generated-sources/protostuff/com/dremio/service/acl/proto/AclProtobuf.java"
    exit 1
fi

echo -e "${GREEN}✓ Protobuf classes generated successfully${NC}"
ls -lh target/generated-sources/protostuff/com/dremio/service/acl/proto/AclProtobuf.java
echo ""

# 3. Compile ACL module
echo -e "${YELLOW}Step 3/4:${NC} Compiling ACL module..."
mvn compile || {
    echo -e "${RED}✗ ACL compilation failed${NC}"
    exit 1
}
echo -e "${GREEN}✓ ACL module compiled successfully${NC}"
echo ""

cd ../..

# 4. Resume build from ACL
echo -e "${YELLOW}Step 4/4:${NC} Completing build (remaining modules)..."
mvn install -DskipTests -rf :dremio-services-acl || {
    echo -e "${RED}✗ Final build failed${NC}"
    exit 1
}

echo ""
echo "============================================"
echo -e "${GREEN}✅ Build Complete!${NC}"
echo "============================================"
echo ""
echo "ACL plugin has been compiled successfully."
echo ""
echo "Generated files:"
echo "  - services/acl/target/generated-sources/protostuff/com/dremio/service/acl/proto/AclProtobuf.java"
echo "  - services/acl/target/dremio-services-acl-*.jar"
echo ""
echo "Next steps:"
echo "  1. Verify: jar tf services/acl/target/dremio-services-acl-*.jar | grep AclProtobuf"
echo "  2. Integration: Modify DACDaemonModule to activate the plugin"
echo ""
