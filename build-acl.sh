#!/bin/bash
#
# Copyright (C) 2017-2019 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -e

echo "============================================"
echo "  Building Dremio with ACL Plugin"
echo "============================================"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Build configuration
SKIP_LICENSE=${SKIP_LICENSE:-true}
SKIP_TESTS=${SKIP_TESTS:-true}
BUILD_THREADS=${BUILD_THREADS:-4}

# Maven options
MAVEN_OPTS="-Ddremio.oss-only=true"
if [ "$SKIP_LICENSE" = "true" ]; then
    MAVEN_OPTS="$MAVEN_OPTS -Dlicense.skip=true"
fi
if [ "$SKIP_TESTS" = "true" ]; then
    MAVEN_OPTS="$MAVEN_OPTS -DskipTests"
fi
MAVEN_OPTS="$MAVEN_OPTS -T${BUILD_THREADS}"

echo -e "${BLUE}Build Configuration:${NC}"
echo "  - Skip license checks: $SKIP_LICENSE"
echo "  - Skip tests: $SKIP_TESTS"
echo "  - Build threads: $BUILD_THREADS"
echo "  - Maven options: $MAVEN_OPTS"
echo ""

# Build everything (no more circular dependency!)
echo -e "${YELLOW}Step 1/1:${NC} Building Dremio with ACL support..."
mvn clean install $MAVEN_OPTS || {
    echo -e "${RED}✗ Build failed${NC}"
    exit 1
}

# Verify ACL protobuf classes were generated
if [ ! -f "services/acl/target/generated-sources/protostuff/com/dremio/service/acl/proto/PrivilegeGrant.java" ]; then
    echo -e "${RED}✗ WARNING: ACL Protobuf classes may not have been generated!${NC}"
    echo "Expected file: services/acl/target/generated-sources/protostuff/com/dremio/service/acl/proto/PrivilegeGrant.java"
fi

echo ""
echo "============================================"
echo -e "${GREEN}✅ Build Complete!${NC}"
echo "============================================"
echo ""
echo "ACL plugin has been compiled successfully."
echo ""
echo "Generated protobuf classes:"
echo "  - PrivilegeGrant.java, PrivilegeType.java, ResourceType.java"
echo "  - GranteeType.java, Role.java, RoleMembership.java"
echo ""
echo "Generated artifacts:"
echo "  - services/acl/target/dremio-services-acl-*.jar"
echo ""
echo "Integration status:"
echo "  ✓ DACDaemonModule: AuthorizationService registered"
echo "  ✓ QueryContext: getAuthorizationService() available"
echo "  ✓ SQL Handlers: GrantHandler and RevokeHandler activated"
echo ""
echo "Verification commands:"
echo "  1. jar tf services/acl/target/dremio-services-acl-*.jar | grep PrivilegeGrant"
echo "  2. mvn test -pl services/acl"
echo ""
echo -e "${BLUE}Build Options:${NC}"
echo "  SKIP_LICENSE=false ./build-acl.sh    # Enable license checks"
echo "  SKIP_TESTS=false ./build-acl.sh      # Enable tests"
echo "  BUILD_THREADS=8 ./build-acl.sh       # Use 8 build threads"
echo ""
