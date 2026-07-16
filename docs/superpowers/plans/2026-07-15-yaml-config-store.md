# YamlConfigStore Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace dual `YamlConfigLoader` + `YamlConfigManager` with a single injected `YamlConfigStore` so UI writes automatically refresh runtime rule indexes.

**Architecture:** One `YamlConfigStore` owns all YAML read/write with mtime cache and change listeners. `BurpExtender` creates the only instance, injects it into `FingerScan`/engines, and registers one listener that reloads `IconHashRuleLoader`. Delete `YamlConfigManager` and `YamlConfigLoader`.

**Tech Stack:** Java 17, SnakeYAML 2.6 (`SafeConstructor`), Burp Montoya API, Gradle shadowJar / Maven assembly, no existing unit-test framework (verify via compile + manual smoke).

**Spec:** `docs/superpowers/specs/2026-07-15-yaml-config-store-design.md`

## Global Constraints

- Keep YAML schema keys: `Load_List`, `Bypass_List`, `Icon_Hash_List` (do not rename fields).
- Fingerprint CRUD is **index-based** (matches UI table rows); rule `id` is data only.
- Write path: write disk → invalidate cache → notify listeners (sync, same thread).
- Listener exceptions: catch per-listener, `Logger.error`, continue others.
- Failed writes: log error, **do not** notify listeners.
- No `System.out.println` in config code.
- Do not change `Config.extractDefaultYamlConfig` beyond what is already done.
- Plugin version remains `3.0.5` (`Constants.PLUGIN_VERSION`, `build.gradle`, `extender/pom.xml`).
- Temporary import/export `YamlConfigStore` instances must **not** register change listeners.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `extender/src/main/java/burp/tdou/fingerscan/config/YamlConfigStore.java` | Single YAML data source |
| Delete | `extender/src/main/java/burp/tdou/fingerscan/config/YamlConfigLoader.java` | Superseded |
| Delete | `extender/src/main/java/burp/tdou/fingerscan/core/YamlConfigManager.java` | Superseded |
| Modify | `extender/src/main/java/burp/tdou/fingerscan/core/rule/YamlRuleEngine.java` | Depend on store |
| Modify | `extender/src/main/java/burp/tdou/fingerscan/core/iconhash/IconHashRuleLoader.java` | Depend on store |
| Modify | `extender/src/main/java/burp/tdou/fingerscan/ui/tab/FingerprintPanel.java` | Use store; drop reload callback |
| Modify | `extender/src/main/java/burp/tdou/fingerscan/ui/tab/IconDataPanel.java` | Use store; drop rule-added callback |
| Modify | `extender/src/main/java/burp/tdou/fingerscan/FingerScan.java` | Injected constructor |
| Modify | `extender/src/main/java/burp/BurpExtender.java` | Create/inject store; one listener |

---

### Task 1: Create `YamlConfigStore`

**Files:**
- Create: `extender/src/main/java/burp/tdou/fingerscan/config/YamlConfigStore.java`
- Reference (do not keep): `extender/src/main/java/burp/tdou/fingerscan/config/YamlConfigLoader.java`
- Reference (do not keep): `extender/src/main/java/burp/tdou/fingerscan/core/YamlConfigManager.java`

**Interfaces:**
- Produces: full `YamlConfigStore` public API used by later tasks (see step 2 code).

- [ ] **Step 1: Create the class skeleton with fields, constructor, path helpers, cache invalidation, and listeners**

Create `YamlConfigStore.java` with package `burp.tdou.fingerscan.config` and these imports:

```java
package burp.tdou.fingerscan.config;

import burp.tdou.common.log.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
```

Class body (fields + lifecycle + listeners):

```java
public class YamlConfigStore {

    private volatile String configFilePath;
    private volatile Map<String, Object> cachedConfig;
    private volatile long cachedLastModified = -1;
    private volatile List<Map<String, Object>> cachedEnabledRules;
    private volatile List<String> cachedScanPaths;
    private volatile long cachedRulesLastModified = -1;

    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public YamlConfigStore(String configFilePath) {
        this.configFilePath = configFilePath != null ? configFilePath : getDefaultConfigPath();
    }

    public void setConfigFilePath(String newPath) {
        if (newPath != null && !newPath.equals(this.configFilePath)) {
            this.configFilePath = newPath;
            invalidateCache();
            notifyListeners();
            Logger.debug("YamlConfigStore: config path updated to %s", newPath);
        }
    }

    public String getConfigFilePath() {
        return configFilePath;
    }

    private static String getDefaultConfigPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "/Applications/Burp Suite Professional.app/Contents/Resources/app/Config_yaml.yaml";
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return (appData != null ? appData : "") + "\\BurpSuite\\Config_yaml.yaml";
        } else {
            return System.getProperty("user.home") + "/.BurpSuite/Config_yaml.yaml";
        }
    }

    public void invalidateCache() {
        cachedConfig = null;
        cachedLastModified = -1;
        cachedEnabledRules = null;
        cachedScanPaths = null;
        cachedRulesLastModified = -1;
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                Logger.error("YamlConfigStore listener error: %s", e.getMessage());
            }
        }
    }

    // read/write methods added in next steps
}
```

- [ ] **Step 2: Implement read APIs (mtime cache)**

Add these methods to `YamlConfigStore` (port from `YamlConfigLoader`, rename class references only):

```java
@SuppressWarnings("unchecked")
public Map<String, Object> readConfig() {
    String currentPath = burp.tdou.fingerscan.common.Config.get("yaml_config_path");
    if (currentPath != null && !currentPath.isEmpty() && !currentPath.equals(configFilePath)) {
        setConfigFilePath(currentPath);
    }

    File file = new File(configFilePath);
    if (!file.exists()) {
        Logger.debug("YAML config file not found: %s", configFilePath);
        return new HashMap<>();
    }

    long lastModified = file.lastModified();
    if (cachedConfig != null && lastModified == cachedLastModified) {
        return cachedConfig;
    }

    try (InputStream is = new FileInputStream(file)) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> data = yaml.load(is);
        cachedConfig = data != null ? data : new HashMap<>();
        cachedLastModified = lastModified;
        cachedEnabledRules = null;
        cachedScanPaths = null;
        return cachedConfig;
    } catch (Exception e) {
        Logger.error("Failed to read YAML config: %s", e.getMessage());
        return cachedConfig != null ? cachedConfig : new HashMap<>();
    }
}

@SuppressWarnings("unchecked")
public List<Map<String, Object>> getFingerprintRules() {
    Map<String, Object> config = readConfig();
    List<Map<String, Object>> loadList = (List<Map<String, Object>>) config.get("Load_List");
    return loadList != null ? loadList : new ArrayList<>();
}

@SuppressWarnings("unchecked")
public List<Map<String, Object>> getEnabledRules() {
    File file = new File(configFilePath);
    long lastModified = file.exists() ? file.lastModified() : 0;
    if (cachedEnabledRules != null && lastModified == cachedRulesLastModified) {
        return cachedEnabledRules;
    }

    List<Map<String, Object>> rules = getFingerprintRules();
    List<Map<String, Object>> enabled = new ArrayList<>();
    for (Map<String, Object> rule : rules) {
        Object loaded = rule.get("loaded");
        if (Boolean.TRUE.equals(loaded)) {
            enabled.add(rule);
        }
    }
    cachedEnabledRules = enabled;
    cachedRulesLastModified = lastModified;
    return enabled;
}

public List<String> getScanPaths() {
    File file = new File(configFilePath);
    long lastModified = file.exists() ? file.lastModified() : 0;
    if (cachedScanPaths != null && lastModified == cachedRulesLastModified) {
        return cachedScanPaths;
    }

    Set<String> pathSet = new HashSet<>();
    for (Map<String, Object> rule : getEnabledRules()) {
        Object urlObj = rule.get("url");
        if (urlObj != null) {
            String url = urlObj.toString().trim();
            if (!url.isEmpty()) {
                pathSet.add(url);
            }
        }
    }
    cachedScanPaths = new ArrayList<>(pathSet);
    return cachedScanPaths;
}

@SuppressWarnings("unchecked")
public List<String> getBypassList() {
    Map<String, Object> config = readConfig();
    List<String> bypassList = (List<String>) config.get("Bypass_List");
    return bypassList != null ? bypassList : new ArrayList<>();
}

@SuppressWarnings("unchecked")
public List<Map<String, Object>> getIconHashRules() {
    Map<String, Object> config = readConfig();
    List<Map<String, Object>> list = (List<Map<String, Object>>) config.get("Icon_Hash_List");
    return list != null ? list : new ArrayList<>();
}

public List<String> generateRecursivePaths(String requestPath) {
    List<String> paths = new ArrayList<>();
    paths.add("/");
    if (requestPath == null || requestPath.isEmpty() || "/".equals(requestPath)) {
        return paths;
    }
    String cleanPath = requestPath.split("\\?")[0].split("#")[0];
    String[] segments = cleanPath.split("/");
    StringBuilder currentPath = new StringBuilder();
    for (int i = 1; i < segments.length; i++) {
        if (!segments[i].isEmpty()) {
            currentPath.append("/").append(segments[i]);
            if (i == segments.length - 1 && segments[i].contains(".")) {
                break;
            }
            String path = currentPath.toString();
            if (!paths.contains(path)) {
                paths.add(path);
            }
        }
    }
    return paths;
}
```

- [ ] **Step 3: Implement write APIs with notify-on-success**

```java
public void writeConfig(Map<String, Object> data) {
    try {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (PrintWriter writer = new PrintWriter(new File(configFilePath))) {
            yaml.dump(data, writer);
        }
        invalidateCache();
        notifyListeners();
    } catch (Exception e) {
        Logger.error("Failed to write YAML config: %s", e.getMessage());
        // do not notify — file not updated
    }
}

@SuppressWarnings("unchecked")
public void addRule(Map<String, Object> rule) {
    Map<String, Object> config = new HashMap<>(readConfig());
    List<Map<String, Object>> loadList = new ArrayList<>(getFingerprintRules());
    rule.remove("id");
    loadList.add(rule);
    config.put("Load_List", loadList);
    writeConfig(config);
}

@SuppressWarnings("unchecked")
public void updateRule(int index, Map<String, Object> updatedRule) {
    Map<String, Object> config = new HashMap<>(readConfig());
    List<Map<String, Object>> loadList = new ArrayList<>(getFingerprintRules());
    if (index < 0 || index >= loadList.size()) {
        return;
    }
    updatedRule.remove("id");
    loadList.set(index, updatedRule);
    config.put("Load_List", loadList);
    writeConfig(config);
}

@SuppressWarnings("unchecked")
public void removeRule(int index) {
    Map<String, Object> config = new HashMap<>(readConfig());
    List<Map<String, Object>> loadList = new ArrayList<>(getFingerprintRules());
    if (index < 0 || index >= loadList.size()) {
        return;
    }
    loadList.remove(index);
    config.put("Load_List", loadList);
    writeConfig(config);
}

@SuppressWarnings("unchecked")
public void addIconHashRule(Map<String, Object> rule) {
    Map<String, Object> config = new HashMap<>(readConfig());
    List<Map<String, Object>> list = new ArrayList<>(getIconHashRules());
    list.add(rule);
    config.put("Icon_Hash_List", list);
    writeConfig(config);
}

@SuppressWarnings("unchecked")
public void updateIconHashRule(int index, Map<String, Object> updatedRule) {
    Map<String, Object> config = new HashMap<>(readConfig());
    List<Map<String, Object>> list = new ArrayList<>(getIconHashRules());
    if (index < 0 || index >= list.size()) {
        return;
    }
    list.set(index, updatedRule);
    config.put("Icon_Hash_List", list);
    writeConfig(config);
}

@SuppressWarnings("unchecked")
public void removeIconHashRule(int index) {
    Map<String, Object> config = new HashMap<>(readConfig());
    List<Map<String, Object>> list = new ArrayList<>(getIconHashRules());
    if (index < 0 || index >= list.size()) {
        return;
    }
    list.remove(index);
    config.put("Icon_Hash_List", list);
    writeConfig(config);
}
```

- [ ] **Step 4: Implement `mergeUpdate` that preserves all keys (fixes Icon_Hash wipe bug)**

```java
@SuppressWarnings("unchecked")
public void mergeUpdate(Map<String, Object> newYamlData) {
    if (newYamlData == null) {
        return;
    }
    // Start from full existing config so Icon_Hash_List and unknown keys survive
    Map<String, Object> merged = new HashMap<>(readConfig());

    List<Map<String, Object>> oldLoadList = getFingerprintRules();
    List<Map<String, Object>> newLoadList = (List<Map<String, Object>>) newYamlData.get("Load_List");
    if (newLoadList != null) {
        List<Map<String, Object>> combined = new ArrayList<>(oldLoadList);
        for (Map<String, Object> newRule : newLoadList) {
            if (!isRuleInList(combined, newRule)) {
                Map<String, Object> copy = new HashMap<>(newRule);
                copy.remove("id");
                combined.add(copy);
            }
        }
        merged.put("Load_List", combined);
    }

    List<String> oldBypass = getBypassList();
    List<String> newBypass = (List<String>) newYamlData.get("Bypass_List");
    if (newBypass != null) {
        List<String> combinedBypass = new ArrayList<>(oldBypass);
        for (String bypass : newBypass) {
            if (!combinedBypass.contains(bypass)) {
                combinedBypass.add(bypass);
            }
        }
        merged.put("Bypass_List", combinedBypass);
    }

    List<Map<String, Object>> oldIcons = getIconHashRules();
    List<Map<String, Object>> newIcons = (List<Map<String, Object>>) newYamlData.get("Icon_Hash_List");
    if (newIcons != null) {
        List<Map<String, Object>> combinedIcons = new ArrayList<>(oldIcons);
        for (Map<String, Object> icon : newIcons) {
            if (!isIconInList(combinedIcons, icon)) {
                combinedIcons.add(new HashMap<>(icon));
            }
        }
        merged.put("Icon_Hash_List", combinedIcons);
    }

    writeConfig(merged);
}

private boolean isRuleInList(List<Map<String, Object>> ruleList, Map<String, Object> targetRule) {
    return ruleList.stream().anyMatch(rule ->
            Objects.equals(rule.get("name"), targetRule.get("name"))
                    && Objects.equals(rule.get("method"), targetRule.get("method"))
                    && Objects.equals(rule.get("path"), targetRule.get("path")));
}

private boolean isIconInList(List<Map<String, Object>> list, Map<String, Object> target) {
    return list.stream().anyMatch(rule ->
            Objects.equals(rule.get("name"), target.get("name"))
                    && Objects.equals(rule.get("murmur_hash"), target.get("murmur_hash"))
                    && Objects.equals(rule.get("md5"), target.get("md5")));
}
```

- [ ] **Step 5: Commit**

```bash
cd /Users/tdou/work/个人研究/ai开发/FingerScan-github
git add extender/src/main/java/burp/tdou/fingerscan/config/YamlConfigStore.java
git commit -m "$(cat <<'EOF'
feat: add YamlConfigStore as single YAML config data source

Introduce YamlConfigStore with mtime cache, index-based CRUD,
mergeUpdate that preserves Icon_Hash_List, and change listeners.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Point rule engines at `YamlConfigStore`

**Files:**
- Modify: `extender/src/main/java/burp/tdou/fingerscan/core/rule/YamlRuleEngine.java`
- Modify: `extender/src/main/java/burp/tdou/fingerscan/core/iconhash/IconHashRuleLoader.java`

**Interfaces:**
- Consumes: `YamlConfigStore` read APIs (`getEnabledRules`, `getScanPaths`, `readConfig`)
- Produces: engines still constructible as `new YamlRuleEngine(store)` / `new IconHashRuleLoader(store)`

- [ ] **Step 1: Update `YamlRuleEngine`**

Replace import and field/constructor types:

```java
// old
import burp.tdou.fingerscan.config.YamlConfigLoader;
private final YamlConfigLoader configLoader;
public YamlRuleEngine(YamlConfigLoader configLoader) { ... }

// new
import burp.tdou.fingerscan.config.YamlConfigStore;
private final YamlConfigStore configStore;
public YamlRuleEngine(YamlConfigStore configStore) {
    this.configStore = configStore;
}
```

Rename all `configLoader.` call sites in the file to `configStore.` (methods `getEnabledRules()` / `getScanPaths()` stay the same). Update the class javadoc line that mentions `YamlConfigLoader` → `YamlConfigStore`.

- [ ] **Step 2: Update `IconHashRuleLoader`**

Same rename pattern:

```java
import burp.tdou.fingerscan.config.YamlConfigStore;

private final YamlConfigStore configStore;

public IconHashRuleLoader(YamlConfigStore configStore) {
    this.configStore = configStore;
}
```

In `loadRules()`, change `configLoader.readConfig()` → `configStore.readConfig()`. Keep `invalidate()`, `loadRules()`, indexes unchanged.

- [ ] **Step 3: Commit**

```bash
git add extender/src/main/java/burp/tdou/fingerscan/core/rule/YamlRuleEngine.java \
        extender/src/main/java/burp/tdou/fingerscan/core/iconhash/IconHashRuleLoader.java
git commit -m "$(cat <<'EOF'
refactor: engines depend on YamlConfigStore

YamlRuleEngine and IconHashRuleLoader now take YamlConfigStore.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Migrate `FingerprintPanel` and `IconDataPanel`

**Files:**
- Modify: `extender/src/main/java/burp/tdou/fingerscan/ui/tab/FingerprintPanel.java`
- Modify: `extender/src/main/java/burp/tdou/fingerscan/ui/tab/IconDataPanel.java`

**Interfaces:**
- Consumes: `YamlConfigStore` write/read APIs from Task 1
- Produces: panels constructible as `new FingerprintPanel(store)` / `new IconDataPanel(store, store)`; **no** `setOnReloadCallback` / `setOnRuleAddedCallback`

- [ ] **Step 1: `FingerprintPanel` — type and field renames**

1. Replace import:
   ```java
   // remove
   import burp.tdou.fingerscan.core.YamlConfigManager;
   // add
   import burp.tdou.fingerscan.config.YamlConfigStore;
   ```
2. Change field:
   ```java
   private YamlConfigStore configStore;
   ```
3. Remove field and methods:
   ```java
   // DELETE these entirely
   private Runnable onReloadCallback;
   public void setOnReloadCallback(Runnable callback) { ... }
   ```
4. Constructor:
   ```java
   public FingerprintPanel(YamlConfigStore configStore) {
       this.configStore = configStore;
       // keep existing init that called loadFingerprintRules / loadIconHashRules
   }
   ```
5. Replace every `configManager.` with `configStore.` then map method names:

| Old call | New call |
|----------|----------|
| `configStore.getConfigFilePath()` | same |
| `configStore.setConfigFilePath(p)` | same |
| `configStore.getFingerprintRules()` | same |
| `configStore.addFingerprintRule(r)` | `configStore.addRule(r)` |
| `configStore.updateFingerprintRule(i, r)` | `configStore.updateRule(i, r)` |
| `configStore.removeFingerprintRule(i)` | `configStore.removeRule(i)` |
| `configStore.getIconHashRules()` | same |
| `configStore.addIconHashRule(r)` | same |
| `configStore.updateIconHashRule(i, r)` | same |
| `configStore.removeIconHashRule(i)` | same |
| `configStore.readYamlConfig()` | `configStore.readConfig()` |
| `configStore.writeYamlConfig(d)` | `configStore.writeConfig(d)` |
| `configStore.mergeUpdateYamlConfig(d)` | `configStore.mergeUpdate(d)` |

6. Import/export temporary instances:
   ```java
   // import
   YamlConfigStore importStore = new YamlConfigStore(importPath);
   Map<String, Object> importData = importStore.readConfig();
   configStore.mergeUpdate(importData);

   // export
   Map<String, Object> configData = configStore.readConfig();
   YamlConfigStore exportStore = new YamlConfigStore(exportPath);
   exportStore.writeConfig(configData);
   ```
   Do **not** call `addChangeListener` on import/export stores.

7. `reloadConfig()` — remove callback invocation:
   ```java
   private void reloadConfig() {
       loadFingerprintRules();
       loadIconHashRules();
       // path change already notified via setConfigFilePath → listeners
       JOptionPane.showMessageDialog(this, "配置重新加载成功！", "提示", JOptionPane.INFORMATION_MESSAGE);
   }
   ```
   Keep `browseConfigFile()` calling `configStore.setConfigFilePath(selectedPath)` + `Config.put("yaml_config_path", selectedPath)`.

- [ ] **Step 2: `IconDataPanel` — type and drop callback**

1. Import `YamlConfigStore` instead of `YamlConfigManager`.
2. Field: `private final YamlConfigStore configStore;`
3. Constructor: `public IconDataPanel(IconHashStore store, YamlConfigStore configStore)`
4. Delete field + setter:
   ```java
   // DELETE
   private Runnable onRuleAddedCallback;
   public void setOnRuleAddedCallback(Runnable callback) { ... }
   ```
5. Where rule is added (~line 425), keep:
   ```java
   configStore.addIconHashRule(result);
   // DELETE: if (onRuleAddedCallback != null) onRuleAddedCallback.run();
   ```
   Store write already notifies listeners.

- [ ] **Step 3: Commit**

```bash
git add extender/src/main/java/burp/tdou/fingerscan/ui/tab/FingerprintPanel.java \
        extender/src/main/java/burp/tdou/fingerscan/ui/tab/IconDataPanel.java
git commit -m "$(cat <<'EOF'
refactor: UI panels use YamlConfigStore

FingerprintPanel and IconDataPanel take YamlConfigStore; remove
manual reload/rule-added callbacks in favor of store listeners.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Inject store into `FingerScan` and `BurpExtender`

**Files:**
- Modify: `extender/src/main/java/burp/tdou/fingerscan/FingerScan.java`
- Modify: `extender/src/main/java/burp/BurpExtender.java`

**Interfaces:**
- Consumes: `YamlConfigStore` from Task 1; panels/engines from Tasks 2–3
- Produces: single runtime instance owned by `BurpExtender`, injected everywhere

- [ ] **Step 1: Rewrite `FingerScan` construction**

Replace YAML-related imports/fields:

```java
import burp.tdou.fingerscan.config.YamlConfigStore;
// remove YamlConfigLoader and YamlConfigManager imports

private YamlConfigStore mConfigStore;
// remove: private YamlConfigManager mConfigManager;
```

Constructor:

```java
public FingerScan(YamlConfigStore configStore) {
    // existing tab setup for DataBoard / ConfigPanel stays
    mConfigStore = configStore;
    try {
        mFingerprintPanel = new FingerprintPanel(configStore);
        addTab("指纹管理", mFingerprintPanel);
    } catch (Exception e) {
        Logger.debug("YAML 指纹面板初始化失败: %s", e.getMessage());
    }
    // DataPanel etc. unchanged
}
```

`initIconDataPanel`:

```java
public void initIconDataPanel(IconHashStore store) {
    mIconDataPanel = new IconDataPanel(store, mConfigStore);
    addTab("图标数据", mIconDataPanel);
    mIconDataPanel.loadIcons();
}
```

`main` test entry:

```java
public static void main(String[] args) {
    Logger.init(true, System.out, System.err);
    Config.init(null);
    // look-and-feel block unchanged
    YamlConfigStore store = new YamlConfigStore(Config.get("yaml_config_path"));
    JFrame frame = new JFrame(Constants.PLUGIN_NAME + " v" + Constants.PLUGIN_VERSION);
    frame.setSize(1400, 700);
    FingerScan fingerScan = new FingerScan(store);
    fingerScan.getDataBoardTab().testInit();
    frame.setContentPane(fingerScan);
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setLocationRelativeTo(null);
    frame.setVisible(true);
}
```

- [ ] **Step 2: Rewrite `BurpExtender` wiring**

1. Imports:
   ```java
   // remove
   import burp.tdou.fingerscan.config.YamlConfigLoader;
   // add
   import burp.tdou.fingerscan.config.YamlConfigStore;
   ```
2. Field: `private YamlConfigStore mYamlConfigStore;` (replace `mYamlConfigLoader`).
3. In `initNewArchitecture()`:
   ```java
   String yamlPath = Config.get("yaml_config_path");
   mYamlConfigStore = new YamlConfigStore(yamlPath);

   YamlRuleEngine yamlEngine = new YamlRuleEngine(mYamlConfigStore);
   CompositeRuleEngine ruleEngine = new CompositeRuleEngine().register(yamlEngine);

   mIconHashRuleLoader = new IconHashRuleLoader(mYamlConfigStore);
   mIconHashRuleLoader.loadRules();
   // rest of architecture setup unchanged
   ```
4. Where UI is created (currently `mFingerScan = new FingerScan();`):
   ```java
   mFingerScan = new FingerScan(mYamlConfigStore);
   ```
5. **Delete** the entire blocks that register:
   - `mFingerScan.getIconDataPanel().setOnRuleAddedCallback(...)`
   - `mFingerScan.getFingerprintPanel().setOnReloadCallback(...)`
6. **After** `mFingerScan` is created and icon panel init finishes, register the single listener:
   ```java
   mYamlConfigStore.addChangeListener(() -> {
       if (mIconHashRuleLoader != null) {
           mIconHashRuleLoader.invalidate();
           mIconHashRuleLoader.loadRules();
       }
       if (mFingerScan != null && mFingerScan.getFingerprintPanel() != null) {
           mFingerScan.getFingerprintPanel().loadIconHashRules();
       }
   });
   ```
   Place this once after both `initIconDataPanel` and fingerprint panel exist (same region where the old callbacks were).

7. Grep for remaining `mYamlConfigLoader` / `YamlConfigLoader` / `YamlConfigManager` / `setOnReloadCallback` / `setOnRuleAddedCallback` in `BurpExtender.java` — must be zero.

- [ ] **Step 3: Commit**

```bash
git add extender/src/main/java/burp/tdou/fingerscan/FingerScan.java \
        extender/src/main/java/burp/BurpExtender.java
git commit -m "$(cat <<'EOF'
refactor: inject single YamlConfigStore from BurpExtender

FingerScan takes store via constructor; BurpExtender registers one
change listener and drops manual invalidate callbacks.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Delete old classes and verify compile

**Files:**
- Delete: `extender/src/main/java/burp/tdou/fingerscan/config/YamlConfigLoader.java`
- Delete: `extender/src/main/java/burp/tdou/fingerscan/core/YamlConfigManager.java`

- [ ] **Step 1: Ensure no remaining references**

```bash
cd /Users/tdou/work/个人研究/ai开发/FingerScan-github
grep -rn "YamlConfigLoader\|YamlConfigManager\|setOnReloadCallback\|setOnRuleAddedCallback\|addFingerprintRule\|readYamlConfig\|mergeUpdateYamlConfig" \
  extender/src --include="*.java"
```

Expected: **no matches** (or only historical comments you intentionally keep — prefer zero).

If any remain, fix them before deleting files.

- [ ] **Step 2: Delete the two old source files**

```bash
git rm extender/src/main/java/burp/tdou/fingerscan/config/YamlConfigLoader.java \
       extender/src/main/java/burp/tdou/fingerscan/core/YamlConfigManager.java
```

- [ ] **Step 3: Compile with Gradle**

```bash
# use project-required JDK 17 + Maven/Gradle env if needed
source ~/.zshrc 2>/dev/null
# if shell helpers exist from prior sessions:
# jdk17;  # optional
cd /Users/tdou/work/个人研究/ai开发/FingerScan-github
./gradlew build -q 2>&1 || gradle build -q 2>&1
```

Expected: **BUILD SUCCESSFUL**, artifact name contains `v3.0.5` (e.g. `FingerScan-v3.0.5.jar` under `build/libs/`).

If Gradle wrapper is missing, use Maven instead:

```bash
# after jdk17 && maven39 if those aliases exist
cd /Users/tdou/work/个人研究/ai开发/FingerScan-github
mvn -q -pl extender package
```

Expected: jar under `extender/target/` named like `FingerScan-v3.0.5.jar` (from assembly `finalName`).

- [ ] **Step 4: Grep for leftover debug prints in config path**

```bash
grep -rn "System.out.println" extender/src/main/java/burp/tdou/fingerscan/config \
  extender/src/main/java/burp/tdou/fingerscan/core/YamlConfigManager.java 2>/dev/null || true
```

Expected: no hits (Manager already deleted).

- [ ] **Step 5: Commit**

```bash
git add -A extender/src/main/java/burp/tdou/fingerscan/config \
          extender/src/main/java/burp/tdou/fingerscan/core
git commit -m "$(cat <<'EOF'
chore: remove YamlConfigLoader and YamlConfigManager

Single YamlConfigStore is now the only YAML config entry point.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Manual smoke checklist (acceptance)

**Files:** none (runtime verification)

- [ ] **Step 1: Load plugin in Burp**

Load `FingerScan-v3.0.5.jar`. Confirm suite tab shows version `v3.0.5` and fingerprint rules load from workdir `Config_yaml.yaml`.

- [ ] **Step 2: Fingerprint CRUD auto-applies**

1. Add a distinctive enabled rule (unique path/body marker).
2. Without clicking "重新加载", send a matching request through proxy/listen or "发送到插件".
3. Expected: match appears in scan results.

- [ ] **Step 3: Icon hash rule auto-applies**

1. From 图标数据, convert an icon to a rule (or add icon hash rule in 指纹管理).
2. Without manual reload, re-trigger icon analysis for that favicon.
3. Expected: match name updates; fingerprint panel icon-hash table refreshes via listener.

- [ ] **Step 4: Path switch and import merge**

1. Switch yaml path in UI to a copy of config; confirm rules reload and scans use new file.
2. Import a yaml that only contains `Load_List`; confirm existing `Icon_Hash_List` entries remain.

- [ ] **Step 5: Final commit if smoke required small fixes**

If smoke found bugs, fix and commit with messages like `fix: ...`. If clean, no extra commit.

Optional: mark spec status to "已实现" in `docs/superpowers/specs/2026-07-15-yaml-config-store-design.md` and commit docs.

```bash
git add docs/superpowers/specs/2026-07-15-yaml-config-store-design.md
git commit -m "$(cat <<'EOF'
docs: mark YamlConfigStore design as implemented

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Spec Coverage Check

| Spec requirement | Task |
|------------------|------|
| Single `YamlConfigStore` instance | Task 1 + 4 |
| Change listeners, auto refresh | Task 1 + 4 |
| Delete Manager + Loader | Task 5 |
| Index-based fingerprint CRUD | Task 1 + 3 |
| Icon hash CRUD | Task 1 + 3 |
| `mergeUpdate` preserves `Icon_Hash_List` | Task 1 Step 4 + Task 6 Step 4 |
| No dead `parseYamlString` / `getIgnoreExtensions` | Task 1 (omitted intentionally) |
| Inject into `FingerScan` | Task 4 |
| Remove manual invalidate callbacks | Task 3 + 4 |
| No System.out in config path | Task 1 + 5 |
| Version 3.0.5 build | Task 5 compile |
| `FingerScan.main` works | Task 4 Step 1 |

## Placeholder / Consistency Check

- No TBD/TODO left in steps.
- Method names consistent: `addRule` / `updateRule(int,)` / `removeRule(int,)` / `mergeUpdate` / `readConfig` / `writeConfig`.
- Field name after migration: `configStore` / `mYamlConfigStore` / `mConfigStore` (not mixed with old Manager names).
