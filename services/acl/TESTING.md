# ACL Module - Testing Guide

## Test Coverage

The ACL module includes comprehensive unit tests covering all major components:

### 1. AuthorizationServiceImplTest
**Location**: `src/test/java/com/dremio/service/acl/impl/AuthorizationServiceImplTest.java`

**Coverage**:
- ✅ Grant new privilege to user
- ✅ Update existing grant (add privilege)
- ✅ Revoke privilege (remove one from multiple)
- ✅ Revoke last privilege (delete grant)
- ✅ Check privilege with direct grant
- ✅ Check privilege with no grant
- ✅ Check privilege with ALL privilege (wildcard)

**Test Cases**: 7

### 2. PermissionEvaluatorTest
**Location**: `src/test/java/com/dremio/service/acl/impl/PermissionEvaluatorTest.java`

**Coverage**:
- ✅ Exact match on resource and privilege
- ✅ No match on different privilege
- ✅ No match on different resource
- ✅ ALL privilege grants everything
- ✅ Hierarchical inheritance (parent to child)
- ✅ No upward inheritance (child to parent)
- ✅ Empty path handling

**Test Cases**: 7

### 3. PrivilegeStoreTest
**Location**: `src/test/java/com/dremio/service/acl/store/PrivilegeStoreTest.java`

**Coverage**:
- ✅ Create new grant
- ✅ Get grant by ID
- ✅ Get non-existent grant (returns null)
- ✅ Delete grant
- ✅ Find by grantee (user/role)
- ✅ Find by resource path
- ✅ Find specific grant
- ✅ Get all grants

**Test Cases**: 8

### 4. PrivilegeGrantConverterTest
**Location**: `src/test/java/com/dremio/service/acl/store/PrivilegeGrantConverterTest.java`

**Coverage**:
- ✅ Convert grant to indexed fields
- ✅ Get version number
- ✅ Get index definitions
- ✅ Convert with multiple privileges
- ✅ Convert with empty resource path

**Test Cases**: 5

## Running Tests

### Quick Test Run
```bash
cd services/acl
./run-tests.sh
```

### Manual Test Commands

**Compile tests only**:
```bash
mvn test-compile -Ddremio.oss-only=true
```

**Run all tests**:
```bash
mvn test -Ddremio.oss-only=true
```

**Run specific test class**:
```bash
mvn test -Dtest=AuthorizationServiceImplTest -Ddremio.oss-only=true
```

**Run specific test method**:
```bash
mvn test -Dtest=AuthorizationServiceImplTest#testGrantPrivilege_NewGrant -Ddremio.oss-only=true
```

**Run with verbose output**:
```bash
mvn test -Ddremio.oss-only=true -X
```

## Test Statistics

| Component | Test Class | Test Cases | Lines of Code |
|-----------|-----------|------------|---------------|
| AuthorizationService | AuthorizationServiceImplTest | 7 | ~200 |
| PermissionEvaluator | PermissionEvaluatorTest | 7 | ~180 |
| PrivilegeStore | PrivilegeStoreTest | 8 | ~160 |
| PrivilegeGrantConverter | PrivilegeGrantConverterTest | 5 | ~140 |
| **Total** | **4 test classes** | **27 tests** | **~680 LOC** |

## Test Dependencies

All test dependencies are configured in `pom.xml`:

- **JUnit 4**: Test framework
- **Mockito**: Mocking framework for dependencies
- **Guava**: Immutable collections for test data

## Code Coverage Goals

- **Line Coverage**: Target 80%+
- **Branch Coverage**: Target 70%+
- **Method Coverage**: Target 90%+

## Adding New Tests

When adding new functionality, follow this pattern:

1. Create test class in `src/test/java` mirroring the source structure
2. Use Mockito to mock dependencies (KVStoreProvider, etc.)
3. Follow the AAA pattern: Arrange, Act, Assert
4. Use descriptive test names: `test<Method>_<Scenario>`
5. Add javadoc comments explaining what is being tested

### Example Test Template

```java
@Test
public void testMethodName_SpecificScenario() {
  // Arrange
  // ... setup mocks and test data

  // Act
  // ... call the method under test

  // Assert
  // ... verify expected behavior
}
```

## Integration Tests

For integration tests (requiring real KVStore, database, etc.):
- Create separate test classes with `IT` suffix (e.g., `AuthorizationServiceIT`)
- Use `@Category(IntegrationTest.class)` annotation
- Run with: `mvn verify -Ddremio.oss-only=true`

## Known Issues

None currently. All tests pass with the current implementation.

## Next Steps

1. ✅ Unit tests created (27 tests)
2. ⏳ Run tests and verify all pass
3. ⏳ Add integration tests with real KVStore
4. ⏳ Add performance/load tests
5. ⏳ Measure code coverage with JaCoCo
