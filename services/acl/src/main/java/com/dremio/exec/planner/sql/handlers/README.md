# SQL Handlers - Moved to sabot/kernel

Les SQL handlers pour GRANT/REVOKE ont été **déplacés vers `sabot/kernel`** pour éviter une dépendance circulaire.

## Localisation actuelle

Les handlers se trouvent maintenant dans :
```
sabot/kernel/src/main/java/com/dremio/exec/planner/sql/handlers/
├── GrantHandler.java
└── RevokeHandler.java
```

## Raison du déplacement

**Problème** : Dépendance circulaire Maven
- `services/acl` avait besoin de `sabot/kernel` (pour QueryContext, Catalog, SqlGrant)
- `sabot/kernel` avait besoin de `services/acl` (pour AuthorizationService)
- Maven détectait un cycle même avec scope `provided`

**Solution** : Déplacer les handlers dans `sabot/kernel`
- Les handlers SQL font logiquement partie de la couche d'exécution SQL
- `sabot/kernel` a déjà tous les autres SQL handlers
- `sabot/kernel` peut dépendre de `services/acl` en scope `provided`
- Pas de cycle !

## Architecture finale

```
services/acl/
├── AuthorizationService (interface)
├── AuthorizationServiceImpl (implémentation)
├── AclStore (stockage)
├── AclValidator (validation)
└── proto/ (modèles de données)

sabot/kernel/
├── QueryContext (avec getAuthorizationService())
├── SabotQueryContext (avec getAuthorizationService())
└── handlers/
    ├── GrantHandler (utilise AuthorizationService)
    └── RevokeHandler (utilise AuthorizationService)

dac/backend/
└── DACDaemonModule (enregistre AuthorizationService)
```

## Activation

Les handlers sont activés via :
1. QueryContext fournit `getAuthorizationService()`
2. DACDaemonModule enregistre AuthorizationService
3. Les handlers sont découverts automatiquement par réflexion

## Utilisation SQL

```sql
GRANT SELECT ON DATASET "myspace"."mytable" TO USER "john.doe";
REVOKE SELECT ON DATASET "myspace"."mytable" FROM USER "john.doe";
```

**Status**: ✅ **READY** - Handlers déplacés et intégrés
