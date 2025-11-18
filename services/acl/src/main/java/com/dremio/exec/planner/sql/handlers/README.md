# SQL Handlers for GRANT/REVOKE (Phase 2)

**Status**: Disabled for Phase 1 MVP

## Files

- `GrantHandler.java.future` - Handler for SQL GRANT command
- `RevokeHandler.java.future` - Handler for SQL REVOKE command

## Why Disabled?

These handlers depend on SQL parser support (`SqlGrant` and `SqlRevoke` classes) that doesn't exist yet in Dremio OSS.

The Dremio SQL parser needs to be extended to support:
1. GRANT privilege syntax
2. REVOKE privilege syntax
3. SqlGrant and SqlRevoke AST nodes

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

## Phase 2: Enable SQL Support

To enable SQL GRANT/REVOKE:

1. Add SQL grammar to `/sabot/grammar/src/main/codegen/includes/parserImpls.ftl`:
   ```
   GRANT privilege ON resource TO USER/ROLE grantee
   REVOKE privilege ON resource FROM USER/ROLE grantee
   ```

2. Create SqlGrant.java and SqlRevoke.java in `/sabot/grammar/src/main/java/com/dremio/exec/planner/sql/parser/`

3. Rename `.java.future` files back to `.java`:
   ```bash
   mv Grant Handler.java.future GrantHandler.java
   mv RevokeHandler.java.future RevokeHandler.java
   ```

4. Update GrantHandler and RevokeHandler to properly inject AuthorizationService from QueryContext

## References

- Design: `/services/acl/DESIGN_ACL_PLUGIN.md`
- Similar patterns: TRUNCATE TABLE handler in `/sabot/kernel/`
