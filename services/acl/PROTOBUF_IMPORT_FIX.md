# Fix: Protobuf Import Errors

## Problème

Après la génération protobuf réussie, la compilation Java échouait avec 47 erreurs :

```
[ERROR] package com.dremio.service.acl.proto.AclProtobuf does not exist
[ERROR] cannot find symbol: class GranteeType
[ERROR] cannot find symbol: class PrivilegeGrant
[ERROR] cannot find symbol: class PrivilegeType
...
```

## Cause Racine

**Confusion entre Google Protobuf et Protostuff** dans les imports Java.

### Google Protobuf (NOT used by Dremio)
Génère un fichier wrapper avec inner classes :
```java
// Generated: AclProtobuf.java
public class AclProtobuf {
    public static class PrivilegeGrant { ... }
    public static enum GranteeType { ... }
}

// Import style:
import com.dremio.service.acl.proto.AclProtobuf.GranteeType;
import com.dremio.service.acl.proto.AclProtobuf.PrivilegeGrant;
```

### Protostuff (USED by Dremio)
Génère des **fichiers individuels** pour chaque message/enum :
```bash
target/generated-sources/protostuff/com/dremio/service/acl/proto/
├── GranteeType.java      # Standalone enum
├── PrivilegeGrant.java   # Standalone class
├── PrivilegeType.java    # Standalone enum
├── ResourceType.java     # Standalone enum
├── Role.java             # Standalone class
└── RoleMembership.java   # Standalone class
```

**Import style correct:**
```java
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.acl.proto.PrivilegeType;
// NO AclProtobuf wrapper!
```

## Solution Appliquée

### 1. Corrections des imports proto (8 fichiers)

**AuthorizationService.java**
```diff
-import com.dremio.service.acl.proto.AclProtobuf.GranteeType;
-import com.dremio.service.acl.proto.AclProtobuf.PrivilegeGrant;
+import com.dremio.service.acl.proto.GranteeType;
+import com.dremio.service.acl.proto.PrivilegeGrant;
```

**GrantHandler.java, RevokeHandler.java**
```diff
-import com.dremio.service.acl.proto.AclProtobuf.GranteeType;
+import com.dremio.service.acl.proto.GranteeType;
```

**AuthorizationServiceImpl.java**
```diff
-import com.dremio.service.acl.proto.AclProtobuf.GranteeType;
-import com.dremio.service.acl.proto.AclProtobuf.PrivilegeGrant;
-import com.dremio.service.acl.proto.AclProtobuf.PrivilegeType;
+import com.dremio.service.acl.proto.GranteeType;
+import com.dremio.service.acl.proto.PrivilegeGrant;
+import com.dremio.service.acl.proto.PrivilegeType;
+import com.dremio.service.acl.proto.ResourceType;

...

-          .setResourceType(com.dremio.service.acl.proto.AclProtobuf.ResourceType.DATASET)
+          .setResourceType(ResourceType.DATASET)
```

**PermissionEvaluator.java, PrivilegeStore.java, PrivilegeStoreCreator.java**
```diff
-import com.dremio.service.acl.proto.AclProtobuf.*;
+import com.dremio.service.acl.proto.*;
```

### 2. Correction de DocumentConverter import

**PrivilegeGrantConverter.java**
```diff
-import com.dremio.datastore.IndexedStore.DocumentConverter;
+import com.dremio.datastore.api.DocumentConverter;
+import com.dremio.datastore.api.DocumentWriter;
```

L'import correct suit le pattern utilisé dans tout le codebase Dremio (voir services/jobs, services/script, etc.).

## Vérification

Après ces corrections, les 47 erreurs de compilation devraient être résolues.

### Test de compilation

```bash
cd /home/user/dremio-oss
./build-acl.sh
```

Ou manuellement :
```bash
cd services/acl
mvn clean compile -Ddremio.oss-only=true
```

### Fichiers générés attendus

```bash
ls target/generated-sources/protostuff/com/dremio/service/acl/proto/
# Output:
GranteeType.java         (707 bytes)
PrivilegeGrant.java      (11K)
PrivilegeType.java       (1.3K)
ResourceType.java        (877 bytes)
Role.java                (7.3K)
RoleMembership.java      (7.8K)
```

## Leçons Apprises

1. **Protostuff ≠ Google Protobuf**
   - Code generation patterns are fundamentally different
   - Protostuff: individual files per message/enum
   - Google Protobuf: wrapper class with inner classes

2. **Import verification**
   - Always check what files are actually generated
   - Don't assume based on other proto implementations

3. **Dremio datastore patterns**
   - Use `com.dremio.datastore.api.DocumentConverter`
   - NOT `com.dremio.datastore.IndexedStore.DocumentConverter`
   - Follow patterns from existing stores (JobsStoreCreator, etc.)

## Commits

- **b2fb65650**: Fix: Correct protobuf imports for Protostuff-generated classes
  - Fixed all 8 Java files with incorrect imports
  - Fixed DocumentConverter import
  - Added ResourceType import and usage

## Références

- Protocol module: `protocol/pom.xml` - Example of working Protostuff config
- Sabot kernel: `sabot/kernel/pom.xml` - Another Protostuff example
- Jobs store: `services/jobs/src/main/java/com/dremio/service/jobs/JobsStoreCreator.java` - DocumentConverter pattern
