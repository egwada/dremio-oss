# SQL Handlers for GRANT/REVOKE

**Status**: ✅ **ACTIVATED** - Handlers enabled and ready for integration

## Files

- `GrantHandler.java` - Handler for SQL GRANT command ✅
- `RevokeHandler.java` - Handler for SQL REVOKE command ✅

## What Changed?

The handlers are now **activated**! SqlGrant and SqlRevoke classes already exist in Dremio OSS (`sabot/kernel/src/main/java/com/dremio/exec/planner/sql/parser/`), so the handlers have been renamed from `.java.future` to `.java` and are ready to use.

## Current Status

✅ SQL parser support exists (`SqlGrant` and `SqlRevoke` classes)
✅ Handlers activated and will be loaded via reflection
✅ Handlers properly structured with error messages for missing integration
⏸️ Waiting for QueryContext integration to provide AuthorizationService

## Phase 1 MVP

The AuthorizationService can be used **programmatically** without SQL syntax:

```java
AuthorizationService aclService = ...;
aclService.grantPrivilege(
    GranteeType.USER,
    "john.doe",
    new NamespaceKey(Arrays.asList("S3", "data", "sales")),
    Privilege.SELECT,
    "admin",
    false
);
```

## Next Step: QueryContext Integration

To fully enable SQL GRANT/REVOKE commands, add AuthorizationService to QueryContext:

1. **Modify QueryContext** (`sabot/kernel/src/main/java/com/dremio/exec/ops/QueryContext.java`):
   ```java
   // Add field
   private final AuthorizationService authorizationService;

   // Add to constructor
   public QueryContext(..., AuthorizationService authorizationService) {
     this.authorizationService = authorizationService;
   }

   // Add getter
   public AuthorizationService getAuthorizationService() {
     return authorizationService;
   }
   ```

2. **Update GrantHandler and RevokeHandler** to use the new getter:
   ```java
   private AuthorizationService getAclService() {
     return context.getAuthorizationService();
   }
   ```

3. **Test** SQL commands:
   ```sql
   GRANT SELECT ON DATASET "myspace"."mytable" TO USER "john.doe";
   REVOKE SELECT ON DATASET "myspace"."mytable" FROM USER "john.doe";
   ```

## References

- Design: `/services/acl/DESIGN_ACL_PLUGIN.md`
- Similar patterns: TRUNCATE TABLE handler in `/sabot/kernel/`
