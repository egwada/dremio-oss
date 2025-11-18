# Compilation Fixes for ACL Module

## Summary of Errors (21 total)

### 1. Protostuff Builder Pattern (5 errors)
**Files**: `AuthorizationServiceImpl.java`, `PrivilegeStore.java`
**Problem**: Code uses `PrivilegeGrant.newBuilder()` assuming Builder pattern exists
**Status**: Builder DOES exist in Protostuff - needs verification after proper generation

### 2. SearchQuery API (8 errors)
**File**: `PrivilegeStore.java`
**Problem**: Wrong API - using `SearchQuery.newBuilder().setEquals()`
**Correct API**: `SearchQueryUtils.newTermQuery(IndexKey, value)` and `SearchQueryUtils.and()`

### 3. DocumentConverter API (2 errors)
**File**: `PrivilegeGrantConverter.java`
**Problem**: Missing `getVersion()` method, wrong `getIndexes()` signature
**Solution**: Add `getVersion()` returning Integer, remove `@Override` from `getIndexes()`

### 4. SqlRevoke.GranteeType (2 errors)
**File**: `RevokeHandler.java`
**Problem**: SqlRevoke has its own inner enum SqlRevoke.GranteeType
**Solution**: Cast correctly from SqlRevoke.GranteeType to our proto GranteeType

### 5. AclCatalog API (3 errors)
**File**: `AclCatalog.java`
**Problems**:
- Missing `visit()` method
- `UserContext.getSerializedUser()` doesn't exist
**Solution**: Add `visit()` method, use correct UserContext API

### 6. PrivilegeStore getStore() (1 error)
**File**: `PrivilegeStore.java`
**Problem**: Type mismatch - returning LegacyKVStore instead of LegacyIndexedStore
**Solution**: Cast properly or use correct API

## Detailed Fixes

### Fix 1: SearchQuery API in PrivilegeStore.java

**Before:**
```java
SearchQuery granteeNameQuery = SearchQuery.newBuilder()
    .setEquals(PrivilegeGrantConverter.GRANTEE_NAME, granteeName)
    .build();

SearchQuery combinedQuery = SearchQuery.newBuilder()
    .setAnd(granteeNameQuery, granteeTypeQuery)
    .build();
```

**After:**
```java
import com.dremio.datastore.SearchQueryUtils;

SearchQuery granteeNameQuery = SearchQueryUtils.newTermQuery(
    PrivilegeGrantConverter.GRANTEE_NAME, granteeName);

SearchQuery combinedQuery = SearchQueryUtils.and(
    granteeNameQuery, granteeTypeQuery);
```

### Fix 2: DocumentConverter in PrivilegeGrantConverter.java

**Add missing method:**
```java
@Override
public Integer getVersion() {
  return 0; // Version 0 for initial implementation
}
```

**Remove @Override from getIndexes()** - it's not an interface method

### Fix 3: SqlRevoke.GranteeType in RevokeHandler.java

**Line 112-113 issue:**
```java
// Current (wrong):
SqlRevoke.GranteeType sqlGranteeType = (SqlRevoke.GranteeType) granteeTypeLiteral.getValue();

// The issue is that SqlRevoke has its own GranteeType enum
// We need to map from SqlRevoke.GranteeType to our proto GranteeType
```

###Fix 4: AclCatalog issues

**Add visit() method:**
```java
@Override
public <T> T visit(Function<Catalog, T> catalogFunction) {
  return getDelegate().visit(catalogFunction);
}
```

**Fix UserContext usage:**
UserContext doesn't have getSerializedUser(), need to find correct method.

## Next Steps

1. Fix SearchQuery usage in PrivilegeStore
2. Fix DocumentConverter in PrivilegeGrantConverter
3. Fix SqlRevoke handler
4. Fix AclCatalog
5. Test compilation
