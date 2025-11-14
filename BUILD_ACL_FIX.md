# Fix: Guide pour Générer les Classes Protobuf Manuellement

## 🔧 Problème

Les classes Protobuf ne se génèrent pas automatiquement pendant `mvn compile` pour le module ACL.

## ✅ Solution Immédiate

### Option 1 : Compiler en Deux Étapes

```bash
# Étape 1 : Compiler jusqu'au module services (avant ACL)
mvn clean install -DskipTests -pl '!services/acl,!services/pubsub-nats,!services/reindexer'

# Étape 2 : Générer les sources Protobuf pour ACL
cd services/acl
mvn generate-sources

# Étape 3 : Vérifier que les classes sont générées
ls -la target/generated-sources/protostuff/com/dremio/service/acl/proto/

# Devrait afficher : AclProtobuf.java

# Étape 4 : Compiler le module ACL
mvn compile

# Étape 5 : Retourner à la racine et finir la compilation
cd ../..
mvn install -DskipTests -rf :dremio-services-acl
```

### Option 2 : Build Script Automatique

Créer un fichier `build-acl.sh` :

```bash
#!/bin/bash
set -e

echo "=== Building Dremio with ACL Plugin ==="

# 1. Build tout sauf ACL
echo "Step 1: Building core modules..."
mvn clean install -DskipTests -pl '!services/acl,!services/pubsub-nats,!services/reindexer' || {
    echo "Core build failed"
    exit 1
}

# 2. Generate ACL protobuf classes
echo "Step 2: Generating ACL protobuf classes..."
cd services/acl
mvn generate-sources || {
    echo "Protobuf generation failed"
    exit 1
}

# Verify generation
if [ ! -f "target/generated-sources/protostuff/com/dremio/service/acl/proto/AclProtobuf.java" ]; then
    echo "ERROR: Protobuf classes not generated!"
    exit 1
fi

echo "✓ Protobuf classes generated successfully"

# 3. Compile ACL module
echo "Step 3: Compiling ACL module..."
mvn compile || {
    echo "ACL compilation failed"
    exit 1
}

cd ../..

# 4. Resume build from ACL
echo "Step 4: Completing build..."
mvn install -DskipTests -rf :dremio-services-acl || {
    echo "Final build failed"
    exit 1
}

echo "=== ✅ Build Complete ==="
echo "ACL plugin compiled successfully!"
```

Rendre exécutable et lancer :

```bash
chmod +x build-acl.sh
./build-acl.sh
```

### Option 3 : Générer Manuellement les Classes (Debug)

Si les options ci-dessus échouent, essayez :

```bash
# Aller dans le module ACL
cd services/acl

# Vérifier que le fichier proto existe
cat src/main/protobuf/privilege.proto | head -20

# Créer manuellement le répertoire de sortie
mkdir -p target/generated-sources/protostuff

# Exécuter seulement le plugin protostuff
mvn com.dremio.build-tools:dremio-protostuff-maven-plugin:compile

# Vérifier la génération
find target/generated-sources -name "*.java"
```

## 🐛 Diagnostic

Si les classes ne se génèrent toujours pas :

```bash
# Vérifier la configuration du plugin
cat services/acl/pom.xml | grep -A 30 "protostuff-maven-plugin"

# Vérifier que le plugin est dans le repo local
ls ~/.m2/repository/com/dremio/build-tools/dremio-protostuff-maven-plugin/

# Activer le debug Maven
cd services/acl
mvn generate-sources -X 2>&1 | grep protostuff
```

## 📝 Pourquoi ce Problème ?

Le plugin `dremio-protostuff-maven-plugin` nécessite que certaines dépendances soient déjà compilées avant de pouvoir générer les classes. En compilant en deux passes, on s'assure que tout est en ordre.

## ✅ Vérification du Succès

Après compilation, vérifier :

```bash
# Les classes Protobuf doivent exister
ls -l services/acl/target/generated-sources/protostuff/com/dremio/service/acl/proto/AclProtobuf.java

# Le JAR doit être créé
ls -l services/acl/target/dremio-services-acl-*.jar

# Les handlers doivent être compilés
find services/acl/target/classes -name "*Handler.class"
```

Vous devriez voir :
- ✅ `AclProtobuf.java` (classes générées)
- ✅ `dremio-services-acl-*.jar` (~80-100KB)
- ✅ `GrantHandler.class` et `RevokeHandler.class`

## 🚀 Après la Compilation

Une fois que tout compile :

1. **Tester que les classes sont bien là** :
```bash
jar tf services/acl/target/dremio-services-acl-*.jar | grep AclProtobuf
```

2. **Passer à l'intégration** :
Le plugin est compilé mais pas encore intégré. Il faut modifier `DACDaemonModule` pour le rendre fonctionnel.

## 💡 Note

Ce problème de génération Protobuf est commun dans les builds Maven multi-modules. La solution en deux passes est une approche standard pour ce type de situation.
