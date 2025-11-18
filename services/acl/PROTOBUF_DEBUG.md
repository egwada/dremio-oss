# Débogage de la Génération Protobuf pour le Module ACL

## Symptôme

Le plugin `dremio-protostuff-maven-plugin` s'exécute avec `BUILD SUCCESS` mais ne génère aucune classe Java.

```
[INFO] --- dremio-protostuff:25.2.0...:compile (generate-sources) @ dremio-services-acl ---
[INFO] BUILD SUCCESS
```

Mais le répertoire `target/generated-sources/protostuff/` n'est même pas créé.

## État Actuel

### Fichier Proto

**Emplacement** : `services/acl/src/main/protobuf/privilege.proto`

**Contenu** :
```protobuf
syntax = "proto2";
package acl;

option java_package = "com.dremio.service.acl.proto";
option optimize_for = SPEED;
option java_outer_classname = "AclProtobuf";

// Messages: PrivilegeGrant, Role, RoleMembership
// Enums: PrivilegeType, ResourceType, GranteeType
```

### Configuration Maven

**Fichier** : `services/acl/pom.xml`

```xml
<plugin>
  <groupId>com.dremio.build-tools</groupId>
  <artifactId>dremio-protostuff-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>generate-sources</id>
      <goals>
        <goal>compile</goal>
      </goals>
      <phase>generate-sources</phase>
      <configuration>
        <protoModules>
          <protoModule>
            <source>src/main/protobuf</source>
            <outputDir>${project.build.directory}/generated-sources/protostuff</outputDir>
            <output>com/dremio/protostuff/compiler/dremio_java_bean.java.stg</output>
            <encoding>UTF-8</encoding>
            <options>
              <property>
                <name>generate_field_map</name>
                <value>true</value>
              </property>
              <property>
                <name>primitive_numbers_if_optional</name>
                <value>true</value>
              </property>
              <property>
                <name>builder_pattern</name>
                <value>true</value>
              </property>
            </options>
          </protoModule>
        </protoModules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

## Corrections Déjà Appliquées

1. ✅ Ajout de `syntax = "proto2";` (Commit: `6cab5c4a0`)
2. ✅ Ajout de `package acl;` (Commit: `6cab5c4a0`)
3. ✅ Ajout de `encoding: UTF-8` (Commit: `e459c226e`)
4. ✅ Ajout de `primitive_numbers_if_optional: true` (Commit: `e459c226e`)

## Comparaison avec Modules qui Fonctionnent

### protocol/pom.xml (✅ FONCTIONNE)
- Utilise `src/main/protobuf`
- A : encoding, generate_field_map, primitive_numbers_if_optional, builder_pattern
- A aussi : `com.dremio.exec.proto` → `com.dremio.exec.proto.beans` (mapping de package)

### provision/common/pom.xml (✅ FONCTIONNE)
- Utilise `src/main/proto` (différent !)
- A seulement : generate_field_map, builder_pattern
- Pas de encoding ni primitive_numbers_if_optional

## Étapes de Débogage Suggérées

### 1. Vérifier la Génération avec Mode Debug

```bash
cd services/acl
mvn clean generate-sources -Ddremio.oss-only=true -X 2>&1 | tee protobuf-debug.log
```

Recherchez dans `protobuf-debug.log` :
- Messages contenant "protostuff"
- Messages d'erreur ou avertissements
- Chemin d'exécution du plugin

### 2. Tester sans Options

Essayez de simplifier la configuration pour voir si le problème vient des options :

```xml
<options>
  <property>
    <name>builder_pattern</name>
    <value>true</value>
  </property>
</options>
```

### 3. Changer le Répertoire Source

Le plugin s'attend peut-être à `src/main/proto` au lieu de `src/main/protobuf` :

```bash
# Tester avec src/main/proto
mv src/main/protobuf src/main/proto
# Modifier pom.xml : <source>src/main/proto</source>
mvn clean generate-sources -Ddremio.oss-only=true
```

### 4. Vérifier les Dépendances du Plugin

Le plugin a-t-il toutes ses dépendances ?

```bash
mvn dependency:tree -Ddremio.oss-only=true | grep protostuff
```

### 5. Compiler le Module Protocol D'abord

Peut-être que le plugin a besoin que protocol soit compilé en premier :

```bash
cd ../../protocol
mvn clean install -DskipTests -Ddremio.oss-only=true
cd ../services/acl
mvn clean generate-sources -Ddremio.oss-only=true
```

### 6. Vérifier la Version du Plugin

```bash
mvn help:effective-pom -Ddremio.oss-only=true | grep -A 5 "dremio-protostuff-maven-plugin"
```

### 7. Tester avec un Fichier Proto Minimal

Créez un fichier proto minimal pour tester :

```protobuf
syntax = "proto2";
package acl.test;

option java_package = "com.dremio.service.acl.proto.test";
option java_outer_classname = "TestProto";

message SimpleMessage {
  optional string name = 1;
}
```

## Hypothèses Possibles

1. **Le plugin ne trouve pas les fichiers proto** - Mais le répertoire et le fichier existent
2. **Erreur de syntaxe dans le proto** - Mais la syntaxe semble correcte selon proto2
3. **Conflit de version** - Le plugin pourrait être incompatible
4. **Manque une dépendance** - Le plugin a besoin de quelque chose qui n'est pas présent
5. **Le plugin écrit ailleurs** - Peu probable mais possible
6. **Problème de permissions** - Le plugin ne peut pas créer le répertoire
7. **Le plugin s'exécute mais échoue silencieusement** - Bug du plugin

## Modules Dremio Utilisant dremio-protostuff-maven-plugin

- ✅ `protocol/` - utilise `src/main/protobuf` - **FONCTIONNE**
- ✅ `provision/common/` - utilise `src/main/proto` - **FONCTIONNE**
- ✅ `sabot/kernel/` - utilise `src/main/protobuf` - **FONCTIONNE**
- ❌ `services/acl/` - utilise `src/main/protobuf` - **NE FONCTIONNE PAS**

## 🎉 RÉSOLUTION

### Le Problème Réel

**Les classes proto SONT générées correctement !** Le problème était dans `build-acl.sh` qui cherchait le mauvais fichier.

### Ce Qui Est Généré

Protostuff génère des **fichiers de classes individuels** (contrairement à Google Protobuf qui génère un fichier wrapper) :

```bash
$ ls target/generated-sources/protostuff/com/dremio/service/acl/proto/
GranteeType.java
PrivilegeGrant.java
PrivilegeType.java
ResourceType.java
Role.java
RoleMembership.java
```

**6 fichiers Java** - un pour chaque message et enum défini dans `privilege.proto`.

### La Confusion

`build-acl.sh` cherchait `AclProtobuf.java` (style Google Protobuf) :

```bash
# INCORRECT - ce fichier n'existe jamais avec Protostuff
if [ ! -f "...proto/AclProtobuf.java" ]; then
    echo "ERROR: Protobuf classes not generated!"
    exit 1
fi
```

Mais ce fichier n'existe pas car :
- **Google Protobuf** génère : `AclProtobuf.java` contenant des inner classes
- **Protostuff** génère : `PrivilegeGrant.java`, `Role.java`, etc. (fichiers séparés)

### Corrections Appliquées

1. ✅ Ajout de `syntax = "proto2";` (Commit: `6cab5c4a0`)
2. ✅ Ajout de `package acl;` (Commit: `6cab5c4a0`)
3. ✅ Ajout de `encoding: UTF-8` (Commit: `e459c226e`)
4. ✅ Ajout de `primitive_numbers_if_optional: true` (Commit: `e459c226e`)
5. ✅ **Fix validation dans build-acl.sh** - Cherche `PrivilegeGrant.java` au lieu de `AclProtobuf.java`

### Vérification

```bash
cd services/acl
mvn clean generate-sources -Ddremio.oss-only=true
ls -lh target/generated-sources/protostuff/com/dremio/service/acl/proto/*.java
```

Devrait afficher les 6 fichiers générés.

## Prochaines Étapes Recommandées

1. ✅ ~~Exécuter avec `-X` (debug) et analyser les logs~~ - Résolu
2. ✅ ~~Comparer exactement avec la configuration de `protocol/pom.xml`~~ - Résolu
3. ❌ ~~Essayer de déplacer vers `src/main/proto`~~ - Pas nécessaire
4. ❌ ~~Si rien ne fonctionne : considérer l'utilisation de `protobuf-maven-plugin` (xolstice) à la place~~ - Pas nécessaire

**La génération protobuf fonctionne parfaitement !**
