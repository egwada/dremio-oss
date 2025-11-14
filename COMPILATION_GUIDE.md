# Guide de Compilation et Test du Plugin ACL

## 📥 Étape 1 : Cloner le Projet

```bash
# Cloner le repository
git clone https://github.com/egwada/dremio-oss.git
cd dremio-oss

# Switch sur la branche ACL
git checkout claude/implement-acl-support-011CV638pBHJwyQ3TqaWtcnh

# Vérifier la branche
git log --oneline -3
```

Vous devriez voir :
```
f490f531c Fix: Resolve cyclic dependency by moving SQL handlers to ACL module
2718b4e6d Add comprehensive compilation guide for ACL plugin
a01dbeccb Fix: Add ACL dependency to sabot-kernel pom.xml
db781378a Implement ACL Plugin MVP (Phase 1)
```

**Note importante** : Le commit `f490f531c` résout un problème de dépendance cyclique en déplaçant les SQL handlers dans le module ACL.

## 🔨 Étape 2 : Compilation

### Option A : Compilation Complète (Recommandé)

```bash
# Depuis la racine du projet
mvn clean install -DskipTests

# ⏱️ Durée attendue : 10-20 minutes
# ✅ Succès attendu : BUILD SUCCESS
```

### Option B : Compilation du Module ACL Uniquement

```bash
# Compiler uniquement le module ACL
cd services/acl
mvn clean install -DskipTests
cd ../..

# Puis compiler sabot-kernel
cd sabot/kernel
mvn clean compile -DskipTests
cd ../..
```

## ⚠️ Dépendances Requises

- **Java 17 ou 21**
- **Maven 3.9+**
- **Mémoire** : 4GB RAM minimum (8GB recommandé)

Vérifier les prérequis :
```bash
java -version    # Doit afficher 17 ou 21
mvn -version     # Doit afficher 3.9+
```

## 🧪 Étape 3 : Vérification de la Compilation

### Vérifier que le Module ACL est Compilé

```bash
# Les classes protobuf doivent être générées
ls -la services/acl/target/generated-sources/protostuff/com/dremio/service/acl/proto/

# Devrait afficher :
# AclProtobuf.java
```

### Vérifier le JAR

```bash
ls -lh services/acl/target/*.jar

# Devrait afficher :
# dremio-services-acl-*.jar (environ 50-100KB)
```

### Vérifier les SQL Handlers

```bash
ls -la sabot/kernel/target/classes/com/dremio/exec/planner/sql/handlers/Grant*.class

# Devrait afficher :
# GrantHandler.class
# GrantHandler$1.class (classes internes)
```

## 🚀 Étape 4 : Tester la Compilation (Sans Exécution)

```bash
# Afficher la structure du module ACL compilé
find services/acl/target/classes -name "*.class" | head -20

# Vérifier les dépendances
mvn dependency:tree -pl services/acl | grep -A 5 "dremio-services-acl"
```

## ⚠️ État Actuel : Plugin NON Intégré

**IMPORTANT** : Le plugin compile mais n'est **pas encore intégré** dans Dremio.

Pour le rendre fonctionnel, il manque :

### ❌ Ce qui MANQUE encore :

1. **Intégration dans DACDaemonModule**
   - Enregistrement du service ACL
   - Wrapper du CatalogService avec AclCatalog

2. **Injection dans les SQL Handlers**
   - Les GrantHandler/RevokeHandler ont des placeholders
   - Il faut injecter l'AuthorizationService

3. **Démarrage de Dremio**
   - Le plugin ne sera pas chargé automatiquement

### ✅ Ce qui FONCTIONNE :

- ✅ Compilation du module ACL
- ✅ Génération des classes Protobuf
- ✅ Compilation des SQL Handlers
- ✅ Dépendances Maven résolues

## 🔧 Prochaines Étapes pour Rendre Fonctionnel

### Option 1 : Vous Intégrez Manuellement

Modifier `/dac/backend/src/main/java/com/dremio/dac/daemon/DACDaemonModule.java` :

```java
// Ajouter l'import
import com.dremio.service.acl.AuthorizationService;
import com.dremio.service.acl.impl.AuthorizationServiceImpl;
import com.dremio.service.acl.catalog.AclCatalog;

// Dans la méthode configure() :
AuthorizationServiceImpl aclService = new AuthorizationServiceImpl(
    provider(LegacyKVStoreProvider.class),
    sabotConfig
);
registry.bind(AuthorizationService.class, aclService);
registry.bindSelf(aclService);

// Wrapper le CatalogService
CatalogServiceImpl catalogService = ... // existing code
AclCatalog aclCatalog = new AclCatalog(catalogService, aclService);
```

### Option 2 : Je Complète l'Intégration (Recommandé)

Dites-moi et je vais :
1. Créer un commit avec l'intégration complète dans DACDaemonModule
2. Corriger l'injection dans les SQL handlers
3. Vous pourrez compiler et démarrer Dremio avec ACL fonctionnel

## 📊 Tailles Approximatives après Compilation

```
services/acl/target/                    ~2 MB
  ├── classes/                          ~100 KB (compiled Java)
  ├── generated-sources/protostuff/     ~50 KB (generated proto classes)
  └── dremio-services-acl-*.jar         ~80 KB
```

## 🐛 Résolution de Problèmes

### Erreur : Cyclic Dependency / ProjectCycleException

**Symptôme** :
```
[ERROR] The projects in the reactor contain a cyclic reference:
Edge between 'dremio-services-acl' and 'dremio-sabot-kernel'
```

**Solution** : Ce problème est résolu dans le commit `f490f531c`
```bash
# Assurez-vous d'avoir le dernier commit
git pull origin claude/implement-acl-support-011CV638pBHJwyQ3TqaWtcnh
git log --oneline -1  # Doit afficher f490f531c ou plus récent
```

**Explication** : Les SQL handlers ont été déplacés de `sabot-kernel` vers `services/acl` pour éviter la dépendance cyclique. Ils sont chargés dynamiquement par réflexion, donc l'emplacement n'importe pas.

### Erreur : "package com.dremio.service.acl does not exist"

**Solution** : La dépendance ACL n'est pas dans le pom.xml
```bash
# Vérifier que le commit a01dbeccb est bien présent
git log --oneline | grep "Add ACL dependency"

# Si absent, pull les derniers commits
git pull origin claude/implement-acl-support-011CV638pBHJwyQ3TqaWtcnh
```

### Erreur : Protobuf classes not generated

**Solution** : Le plugin protostuff n'a pas été exécuté
```bash
# Forcer la génération
cd services/acl
mvn clean generate-sources
ls -la target/generated-sources/protostuff/
```

### Erreur : Out of Memory

**Solution** : Augmenter la mémoire Maven
```bash
export MAVEN_OPTS="-Xmx4g -XX:MaxPermSize=512m"
mvn clean install -DskipTests
```

## 📝 Tests Unitaires (Prochaine Étape)

Pour l'instant, le module compile mais les tests ne sont pas encore écrits.

```bash
# Cette commande échouera (pas de tests)
cd services/acl
mvn test
```

## 💡 Que Faire Ensuite ?

1. **Pour utiliser le plugin** : Demandez-moi de compléter l'intégration DACDaemonModule
2. **Pour comprendre le code** : Explorez les fichiers dans `services/acl/src/main/java/`
3. **Pour contribuer** : Lisez `DESIGN_ACL_PLUGIN.md` pour l'architecture complète

## 📞 Besoin d'Aide ?

Si vous rencontrez des erreurs, partagez :
```bash
# Le message d'erreur complet
mvn clean compile 2>&1 | tail -100

# Version Java/Maven
java -version
mvn -version

# État git
git log --oneline -5
```
