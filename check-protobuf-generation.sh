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
