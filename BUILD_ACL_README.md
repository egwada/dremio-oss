# Build ACL Module - Guide d'utilisation

## Script de build : `build-acl.sh`

Le script `build-acl.sh` permet de compiler le module ACL avec une gestion optimisée des dépendances.

### Utilisation de base

```bash
./build-acl.sh
```

Par défaut, le script :
- ✅ Ignore les vérifications de licence (`-Dlicense.skip=true`)
- ✅ Ignore les tests (`-DskipTests`)
- ✅ Utilise 4 threads de compilation parallèle

### Options de configuration

Les options se passent en variables d'environnement :

#### 1. Désactiver le skip de licence
```bash
SKIP_LICENSE=false ./build-acl.sh
```

#### 2. Activer les tests
```bash
SKIP_TESTS=false ./build-acl.sh
```

#### 3. Augmenter les threads de compilation
```bash
BUILD_THREADS=8 ./build-acl.sh
```

#### 4. Combiner plusieurs options
```bash
SKIP_LICENSE=false SKIP_TESTS=false BUILD_THREADS=8 ./build-acl.sh
```

### Étapes de build

Le script exécute 6 étapes pour gérer la dépendance circulaire entre ACL et sabot/kernel :

1. **Build dépendances minimales** : Compile `services/base-rpc`, `common/legacy` (sans sabot/kernel)
2. **Build ACL d'abord** : Génère protobuf + compile + installe le module ACL
3. **Build sabot/kernel** : Compile sabot/kernel (qui dépend de ACL en scope 'provided')
4. **Build modules core** : Compile tous les modules restants (sauf pubsub-nats, reindexer)
5. **Build DAC backend** : Compile le backend qui utilise AuthorizationService
6. **Installation finale** : Complète le build de tous les modules restants

**Note sur la dépendance circulaire** :
- `sabot/kernel` a besoin de ACL pour compiler (QueryContext référence AuthorizationService)
- ACL a besoin de sabot/kernel pour compiler (SQL handlers référencent QueryContext)
- Solution : ACL est en scope `provided` dans sabot/kernel, et on compile ACL en premier

### Gestion des erreurs de licence

Si vous rencontrez l'erreur :
```
Some files do not have the expected license header. Run license:format to update them.
```

**Solution 1** : Utiliser le script avec `SKIP_LICENSE=true` (par défaut)
```bash
./build-acl.sh
```

**Solution 2** : Formater les licences automatiquement
```bash
mvn license:format
```

**Solution 3** : Activer les vérifications après avoir formaté
```bash
mvn license:format
SKIP_LICENSE=false ./build-acl.sh
```

### Configuration Maven Toolchains

Assurez-vous que votre fichier `~/.m2/toolchains.xml` est correctement configuré :

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>11</version>
      <vendor>sun</vendor>
    </provides>
    <configuration>
      <jdkHome>/home/ccharly/jdks/jdk-11.0.27+6</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>17</version>
      <vendor>sun</vendor>
    </provides>
    <configuration>
      <jdkHome>/home/ccharly/jdks/jdk-17.0.9+9</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>21</version>
      <vendor>sun</vendor>
    </provides>
    <configuration>
      <jdkHome>/home/ccharly/jdks/jdk-21.0.7+6</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

### Vérification du build

Après un build réussi :

```bash
# Vérifier le JAR généré
jar tf services/acl/target/dremio-services-acl-*.jar | grep PrivilegeGrant

# Lancer les tests ACL
mvn test -pl services/acl

# Vérifier l'intégration
cat services/acl/ACL_INTEGRATION_STATUS.md
```

### Build complet Dremio avec ACL

Pour un build complet incluant tous les modules :

```bash
# Build complet avec skip licence et skip tests
mvn clean install -Ddremio.oss-only=true -Dlicense.skip=true -DskipTests

# Build complet avec tests
SKIP_TESTS=false ./build-acl.sh
mvn verify -Ddremio.oss-only=true -Dlicense.skip=true
```

### Dépendances requises

**Ordre de compilation** (géré automatiquement par le script) :

1. **Dépendances de base** :
   - `services/base-rpc` - Infrastructure RPC
   - `common/legacy` - Classes communes legacy

2. **Module ACL** : Compilé en premier pour résoudre la dépendance circulaire

3. **sabot/kernel** : Dépend de ACL avec scope `provided`
   ```xml
   <dependency>
     <groupId>com.dremio.services</groupId>
     <artifactId>dremio-services-acl</artifactId>
     <version>${project.version}</version>
     <scope>provided</scope>
   </dependency>
   ```
   Le scope `provided` signifie :
   - ACL est nécessaire pour la compilation
   - Mais ACL sera fourni au runtime par DACDaemonModule
   - Évite d'inclure ACL dans le classpath de kernel

4. **Reste des modules** : Compilés normalement

Le script gère automatiquement cet ordre complexe.

### Troubleshooting

#### Erreur : "Protobuf classes not generated"
```bash
# Nettoyer et régénérer
cd services/acl
mvn clean generate-sources -Ddremio.oss-only=true
```

#### Erreur : "Core dependencies build failed"
```bash
# Vérifier les modules de base
mvn clean install -pl services/base-rpc,common/legacy,sabot/kernel -am -Dlicense.skip=true
```

#### Erreur réseau : "maven.dremio.com: Temporary failure"
- Vérifier la configuration réseau
- Vérifier le fichier `.mvn/extensions.xml` (extensions désactivées si problème réseau)

### Structure des fichiers générés

Après un build réussi :

```
services/acl/
├── target/
│   ├── classes/                           # Classes compilées
│   ├── generated-sources/
│   │   └── protostuff/
│   │       └── com/dremio/service/acl/proto/
│   │           ├── PrivilegeGrant.java   # Généré
│   │           ├── PrivilegeType.java     # Généré
│   │           ├── ResourceType.java      # Généré
│   │           ├── GranteeType.java       # Généré
│   │           ├── Role.java              # Généré
│   │           └── RoleMembership.java    # Généré
│   └── dremio-services-acl-*.jar         # Artifact final
```

### Intégration dans Dremio

L'intégration ACL est **complète** :

✅ **DACDaemonModule** : AuthorizationService enregistré
✅ **QueryContext** : `getAuthorizationService()` disponible
✅ **SQL Handlers** : `GrantHandler` et `RevokeHandler` activés

Voir `services/acl/INTEGRATION_GUIDE.md` pour plus de détails.
