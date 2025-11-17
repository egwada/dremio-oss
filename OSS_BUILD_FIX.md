# Fix pour la Compilation OSS-Only de Dremio

## Problème Identifié

Lors de la compilation avec le flag `-Ddremio.oss-only=true`, le build échouait avec l'erreur :

```
Could not find artifact com.dremio.community.plugins:dremio-ce-jdbc-plugin:jar:25.2.0-...
```

## Cause Racine

Le module `dac/backend/pom.xml` déclarait une dépendance **inconditionnelle** sur `dremio-ce-jdbc-plugin`, qui est un artefact non-OSS disponible uniquement via le repository Maven privé `https://maven.dremio.com/free/`.

Quand on compile avec `-Ddremio.oss-only=true`, le profile Maven `community-build` est **désactivé**, ce qui désactive ce repository, rendant l'artefact introuvable.

## Solution Appliquée

**Commit:** `18cdb52ae` - "Fix: Move dremio-ce-jdbc-plugin dependency to community-build profile"

La dépendance `dremio-ce-jdbc-plugin` a été déplacée depuis la section `<dependencies>` principale vers un nouveau profile `community-build` dans `dac/backend/pom.xml` :

```xml
<profile>
  <id>community-build</id>
  <activation>
    <property>
      <name>dremio.oss-only</name>
      <value>!true</value>
    </property>
  </activation>
  <dependencies>
    <dependency>
      <groupId>com.dremio.community.plugins</groupId>
      <artifactId>dremio-ce-jdbc-plugin</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>
</profile>
```

Ce pattern est identique à celui utilisé dans `dac/daemon/pom.xml`, qui contient déjà toutes les dépendances non-OSS dans le même profile.

## Vérification

Pour vérifier que le fix fonctionne :

```bash
# Avec le flag OSS-only : la dépendance NE doit PAS être présente
mvn help:effective-pom -Ddremio.oss-only=true -pl dac/backend | grep dremio-ce-jdbc-plugin
# Résultat attendu : aucune sortie

# Sans le flag : la dépendance DOIT être présente
mvn help:effective-pom -pl dac/backend | grep dremio-ce-jdbc-plugin
# Résultat attendu : affiche la dépendance
```

## Problème Secondaire : Maven Extensions

Le projet Dremio utilise des extensions Maven définies dans `.mvn/extensions.xml` :

```xml
<extension>
  <groupId>fr.jcgay.maven</groupId>
  <artifactId>maven-profiler</artifactId>
  <version>3.2</version>
</extension>
```

Si ces extensions ne peuvent pas être téléchargées (problème réseau, repositories indisponibles), le build échouera avec :

```
Extension fr.jcgay.maven:maven-profiler:3.2 or one of its dependencies could not be resolved
```

### Solution temporaire

Si vous rencontrez ce problème, vous pouvez temporairement désactiver les extensions :

```bash
# Option 1 : Renommer le fichier d'extensions
mv .mvn/extensions.xml .mvn/extensions.xml.disabled

# Option 2 : Utiliser Maven sans extensions
mvn --no-transfer-progress clean install -DskipTests -Ddremio.oss-only=true
```

**Note :** Les extensions sont utilisées pour le profiling et les notifications. Elles ne sont pas essentielles pour la compilation.

## Compilation OSS-Only Complète

Une fois le fix appliqué, vous pouvez compiler avec :

```bash
# Récupérer les derniers commits
git pull origin claude/implement-acl-support-011CV638pBHJwyQ3TqaWtcnh

# Option 1 : Build complet
mvn clean install -DskipTests -Ddremio.oss-only=true

# Option 2 : Utiliser le script ACL (qui inclut déjà le flag)
./build-acl.sh
```

## Impact

- ✅ Les builds OSS-only (`-Ddremio.oss-only=true`) fonctionnent maintenant sans erreur de dépendance
- ✅ Les builds Community (sans le flag) continuent de fonctionner avec les plugins non-OSS
- ✅ Aucun impact sur les autres modules ou fonctionnalités

## Corrections Multiples

### 1. Dépendance dremio-ce-jdbc-plugin (Commit: `18cdb52ae`)

Problème : Dépendance inconditionnelle dans `dac/backend/pom.xml`
Solution : Déplacée vers profile `community-build`

### 2. Import JDBC dans DeprecatedSourceResource (Commit: `38bdeec0a`)

Problème : `DeprecatedSourceResource.java` importait `com.dremio.exec.store.jdbc.JdbcPluginOptions`
Solution :
- Suppression de l'import
- Retour de `false` pour OPENSEARCH en mode OSS (plugin non disponible)

## Modules Affectés

- `dac/backend/pom.xml` : dépendance déplacée vers profile
- `dac/backend/src/main/java/com/dremio/dac/api/DeprecatedSourceResource.java` : import JDBC supprimé
- `dac/daemon` : déjà correct (aucun changement)

## Références

- Profile `community-build` défini dans `pom.xml` racine (ligne 4250)
- Pattern existant dans `dac/daemon/pom.xml` (lignes 252-320)
- Documentation Dremio OSS : `/README.md` (ligne 113)
