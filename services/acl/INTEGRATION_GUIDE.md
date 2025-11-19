# ACL Module - Integration Guide

## Overview

This guide explains how to integrate the ACL module into Dremio to make it functional and observable.

## Prerequisites

✅ ACL module compiles successfully
✅ Unit tests compile and pass
✅ All 26 compilation errors fixed

## Integration Steps

### Step 1: Add Dependency to DAC Backend

**File**: `dac/backend/pom.xml`

**Action**: Add ACL module dependency

```xml
<!-- Add after other dremio-services dependencies -->
<dependency>
  <groupId>com.dremio.services</groupId>
  <artifactId>dremio-services-acl</artifactId>
  <version>${project.version}</version>
</dependency>
```

**Location**: Around line 100-150, with other services dependencies

---

### Step 2: Wire AuthorizationService in DACDaemonModule

**File**: `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java`

#### 2.1 Add Import

```java
import com.dremio.service.acl.AuthorizationService;
import com.dremio.service.acl.impl.AuthorizationServiceImpl;
```

#### 2.2 Bind the Service

Find the section where services are registered (around line 500-800) and add:

```java
// ACL Service (optional - disabled by default via config)
registry.bind(
    AuthorizationService.class,
    new AuthorizationServiceImpl(
        registry.provider(LegacyKVStoreProvider.class),
        bootstrap.getConfig()
    )
);

// Register in service set so it starts/stops with Dremio
registry.bindSelf(AuthorizationService.class);
```

**Note**: The service checks `dremio.acl.enabled` configuration before starting.

---

### Step 3: Enable ACL Service via Configuration

**File**: `conf/dremio.conf` (or `dremio-env`)

**Add Configuration**:

```hocon
dremio.acl {
  # Enable ACL service
  enabled: true

  # Strict mode: reject unauthorized access
  strict_mode: false

  # Public sources (no ACL checks)
  public_sources: ["Samples"]

  # Super admins (bypass all checks)
  super_admins: ["admin", "dremio"]

  # Cache settings
  cache {
    max_size: 10000
    ttl_minutes: 60
  }
}
```

---

### Step 4: Verify Integration (Without SQL Handlers)

After integration, you can verify the service is running:

#### 4.1 Check Logs

Start Dremio and look for:

```
INFO  [main] c.d.s.acl.impl.AuthorizationServiceImpl - Starting AuthorizationService
INFO  [main] c.d.s.acl.impl.AuthorizationServiceImpl - AuthorizationService started (mode: enabled, strictMode: false)
```

#### 4.2 Check Service Registry

The service will appear in the Dremio service registry.

#### 4.3 Test Programmatically

You can create a test endpoint or use existing test infrastructure to:

```java
// Grant a privilege
authService.grantPrivilege(
    GranteeType.USER,
    "testuser",
    new NamespaceKey(Arrays.asList("myspace", "mytable")),
    Privilege.SELECT,
    "admin",
    false
);

// Check permission
boolean hasAccess = authService.checkPermission(
    "testuser",
    new NamespaceKey(Arrays.asList("myspace", "mytable")),
    Privilege.SELECT
);
```

---

## Step 5: Activate SQL GRANT/REVOKE Handlers

**Current Status**: SQL handlers are disabled (`.java.future` extension)

**Why Disabled**: They depend on SQL parser extensions that need to be implemented.

### Option A: Enable with Limited Functionality

#### 5.1 Rename Handler Files

```bash
cd services/acl/src/main/java/com/dremio/exec/planner/sql/handlers/
mv GrantHandler.java.future GrantHandler.java
mv RevokeHandler.java.future RevokeHandler.java
```

#### 5.2 Register Handlers

**File**: `sabot/kernel/src/main/java/com/dremio/exec/planner/sql/SqlConverter.java`

Find where SQL handlers are registered and add:

```java
// Add imports
import com.dremio.exec.planner.sql.handlers.direct.GrantHandler;
import com.dremio.exec.planner.sql.handlers.direct.RevokeHandler;

// In handler registration section
handlers.add(SqlGrant.class, new GrantHandler(catalog));
handlers.add(SqlRevoke.class, new RevokeHandler(catalog));
```

#### 5.3 Expected Issues

⚠️ **SQL Parser Extensions Needed**:
- `SqlGrant` parser class
- `SqlRevoke` parser class
- `Privilege` enum in parser

These are referenced but not yet implemented.

### Option B: Wait for SQL Parser Implementation

**Recommended Approach**: Keep handlers disabled until SQL parser extensions are implemented in Phase 2.

---

## Testing the Integration

### Test 1: Service Startup

```bash
# Start Dremio
./dremio-start

# Check logs for ACL service startup
tail -f log/server.log | grep AuthorizationService
```

**Expected**:
```
INFO  [main] c.d.s.acl.impl.AuthorizationServiceImpl - Starting AuthorizationService
INFO  [main] c.d.s.acl.impl.AuthorizationServiceImpl - ACL service enabled (strict_mode: false)
```

### Test 2: Grant Programmatically

Create a simple test class:

```java
public class AclIntegrationTest {
  @Inject
  private AuthorizationService aclService;

  @Test
  public void testGrantAndCheck() throws Exception {
    // Grant SELECT on table
    aclService.grantPrivilege(
        GranteeType.USER,
        "bob",
        new NamespaceKey(Arrays.asList("Sales", "Customers")),
        Privilege.SELECT,
        "admin",
        false
    );

    // Verify permission
    boolean hasAccess = aclService.checkPermission(
        "bob",
        new NamespaceKey(Arrays.asList("Sales", "Customers")),
        Privilege.SELECT
    );

    assertTrue(hasAccess);
  }
}
```

### Test 3: Verify KVStore

Check that grants are persisted:

```bash
# Access Dremio admin console
# Navigate to Advanced Settings > Key-Value Store
# Look for "acl_privileges" store
```

---

## Configuration Reference

### Full Configuration Options

```hocon
dremio.acl {
  # Enable/disable ACL service
  enabled: true

  # Strict mode
  # true = reject unauthorized access
  # false = log warnings but allow access
  strict_mode: false

  # Sources that don't require ACL checks
  public_sources: [
    "Samples",
    "Information Schema",
    "sys"
  ]

  # Users who bypass all ACL checks
  super_admins: [
    "admin",
    "dremio",
    "$dremio$"
  ]

  # Permission cache settings
  cache {
    # Maximum number of cached permission checks
    max_size: 10000

    # Time-to-live for cached entries (minutes)
    ttl_minutes: 60

    # Enable cache statistics
    record_stats: false
  }

  # Audit logging
  audit {
    # Enable audit log for grant/revoke operations
    enabled: true

    # Log level (INFO, DEBUG, TRACE)
    level: "INFO"
  }
}
```

---

## Troubleshooting

### Service Doesn't Start

**Symptom**: No log messages from AuthorizationService

**Check**:
1. Is `dremio.acl.enabled=true` in config?
2. Is dependency added to `dac/backend/pom.xml`?
3. Is service registered in DACDaemonModule?
4. Check for errors in `log/server.log`

### KVStore Not Initialized

**Symptom**: NullPointerException when accessing privilegeStore

**Fix**: Ensure LegacyKVStoreProvider is available when service starts.

### SQL Handlers Not Working

**Expected**: SQL handlers are disabled by default.

**Solution**: Wait for Phase 2 (SQL parser extensions) or implement limited functionality.

---

## Next Steps After Integration

1. ✅ Service starts and logs correctly
2. **Create REST API endpoints** for ACL management
3. **Implement catalog interception** to check permissions
4. **Add UI for ACL management**
5. **Phase 2: Enable SQL GRANT/REVOKE**
6. **Add comprehensive integration tests**
7. **Performance testing and optimization**

---

## Files Modified for Integration

1. `dac/backend/pom.xml` - Add dependency
2. `dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` - Wire service
3. `conf/dremio.conf` - Add configuration
4. (Optional) SQL handler registration if enabling Phase 2

---

## Rollback Instructions

If integration causes issues:

1. **Remove from pom.xml**:
   ```bash
   # Comment out or remove ACL dependency
   ```

2. **Remove from DACDaemonModule**:
   ```bash
   # Comment out or remove bind(AuthorizationService.class)
   ```

3. **Disable in config**:
   ```hocon
   dremio.acl.enabled: false
   ```

4. **Rebuild**:
   ```bash
   mvn clean install -DskipTests
   ```

---

## Status

- ✅ Module code complete and tested
- ✅ Module compiles successfully
- ⏳ **Integration pending** (this guide)
- ⏳ REST API endpoints
- ⏳ Catalog interception
- ⏳ Phase 2: SQL syntax

