# YamlConfigStore 统一配置架构设计

**日期**: 2026-07-15  
**状态**: 待评审  
**范围**: FingerScan-github 指纹/图标规则 YAML 配置的读写与运行时同步

---

## 1. 背景与问题

当前同一份 `Config_yaml.yaml` 被 **3 个对象、2 份缓存** 包着:

```
BurpExtender.initNewArchitecture()
  └─ new YamlConfigLoader(path)          ← 实例 A（运行时：YamlRuleEngine / IconHashRuleLoader）
       └─ 缓存 A（mtime）

FingerScan()  ← 无参构造，内部自建
  ├─ new YamlConfigLoader(path)          ← 实例 B（只为 getConfigFilePath()）
  └─ new YamlConfigManager(path)         ← 实例 C（UI 读写，无缓存，独立 FileInputStream）
```

### 具体病灶

| # | 问题 | 表现 |
|---|------|------|
| 1 | 多实例缓存不通气 | UI 经 Manager 写文件后，运行时实例 A 全靠 mtime 巧合失效 |
| 2 | 手动 `invalidateCache` 打补丁 | `BurpExtender` 281/296 行人肉清缓存 + 重载，漏一处就是 bug |
| 3 | 旧类重复实现 | `YamlConfigManager`（`@version 2.0`）重复了 `readConfig / getFingerprintRules / generateRecursivePaths / getScanPaths`，还残留 `System.out.println` |
| 4 | 隐藏依赖 | `FingerScan` 无参构造内部自建依赖，无法注入，是多实例的根源 |

---

## 2. 目标

1. **单一数据源**：全局只存在一个配置对象，UI 与运行时共用。
2. **写后自动刷新**：任何写操作完成后，运行时规则索引自动重建，删除所有手动 `invalidateCache` 调用点。
3. **删除重复代码**：移除 `YamlConfigManager`，清理 `System.out.println`。
4. **注入式初始化**：`FingerScan` 改为有参构造，由 `BurpExtender` 注入 store。

**非目标（YAGNI）**：
- 不改 YAML 文件格式 / 字段 schema
- 不改 `Config.extractDefaultYamlConfig` 的首次释放逻辑（已在本会话修好）
- 不引入异步线程做 reload（索引重建很快，同步即可）
- 不重构 `Config`（`config.json`）本身

---

## 3. 目标架构

```
插件启动（BurpExtender）
 │
 ① Config.init(workDir)
 │    └─ extractDefaultYamlConfig() → 首次从 jar 释放内置 Config_yaml.yaml
 │    └─ 路径写入 Config["yaml_config_path"]
 │
 ② mYamlConfigStore = new YamlConfigStore(yaml_config_path)   ← 全局唯一
 │
 ③ 组装运行时 + 注册监听者
 │    YamlRuleEngine(store)
 │    IconHashRuleLoader(store)
 │    store.addChangeListener(() -> {
 │        iconHashRuleLoader.invalidate();
 │        iconHashRuleLoader.loadRules();
 │        // YamlRuleEngine 无独立索引，下次 match 时走 store 缓存即可
 │    })
 │
 ④ mFingerScan = new FingerScan(store)   ← 有参构造，注入同一个 store
 │    FingerprintPanel(store)
 │    IconDataPanel(store)
 │
 ⑤ 不再有 YamlConfigManager；不再有第二份 YamlConfigLoader
```

### 读路径

任意调用方 → `store.readConfig()` / `getEnabledRules()` / `getIconHashRules()`：

1. 检查 `Config.get("yaml_config_path")` 是否被 UI 改过；改过则切路径并清缓存。
2. 比对文件 mtime：命中缓存 → 直接返回；未命中 → `SafeConstructor` 解析 → 更新缓存 → 返回。

### 写路径 + 自动刷新

```
FingerprintPanel / IconDataPanel
        │
        ▼
store.addRule / updateRule / removeRule / addIconHashRule / ...
        │
        ├─ 写文件
        ├─ 清自己的缓存
        └─ 通知所有 listener ──┬─► IconHashRuleLoader.reload()
                               └─► （其他监听者）
```

`BurpExtender` 现有两处手动 `invalidateCache() + loadRules()`（约 281、296 行）**全部删除**，改为启动时一次性 `addChangeListener`。

---

## 4. 组件设计

### 4.1 `YamlConfigStore`（新建，由 `YamlConfigLoader` 演进）

**包路径**: `burp.tdou.fingerscan.config.YamlConfigStore`  
**旧类**: `YamlConfigLoader` 删除（或重命名后迁移，不保留双类）。

#### 公开 API

```java
public class YamlConfigStore {

    // ---- 生命周期 ----
    public YamlConfigStore(String configFilePath);
    public void setConfigFilePath(String newPath);   // 切路径 + 清缓存 + 通知
    public String getConfigFilePath();

    // ---- 读（带 mtime 缓存）----
    public Map<String, Object> readConfig();
    public List<Map<String, Object>> getFingerprintRules();
    public List<Map<String, Object>> getEnabledRules();
    public List<String> getScanPaths();
    public List<String> getBypassList();
    public List<Map<String, Object>> getIconHashRules();
    public List<String> generateRecursivePaths(String requestPath);

    // ---- 写（写完清缓存 + 通知）----
    public void writeConfig(Map<String, Object> data);
    public void addRule(Map<String, Object> rule);
    public void updateRule(int index, Map<String, Object> rule);
    public void removeRule(int index);
    public void addIconHashRule(Map<String, Object> rule);
    public void updateIconHashRule(int index, Map<String, Object> rule);
    public void removeIconHashRule(int index);
    public void mergeUpdate(Map<String, Object> importData);  // 导入合并

    // ---- 缓存 / 观察者 ----
    public void invalidateCache();                       // 外部强制清缓存（一般不需要）
    public void addChangeListener(Runnable listener);
    public void removeChangeListener(Runnable listener);
}
```

#### 关键实现约定

| 项 | 约定 |
|----|------|
| 反序列化 | 继续用 SnakeYAML `SafeConstructor` |
| 缓存键 | 文件 mtime；路径切换时强制失效 |
| 写后行为 | `writeConfig` 及所有写方法：写盘 → `invalidateCache()` → `notifyListeners()` |
| 通知线程 | **同步、当前线程**（UI 事件线程）；索引重建很快，不引入后台线程 |
| 监听器异常 | 单个 listener 抛异常不影响其他 listener，记 `Logger.error` |
| 路径切换 | `setConfigFilePath` 同样清缓存 + 通知 |
| 默认路径 | 保留现有平台感知默认路径逻辑（mac/win/linux），仅作构造时 fallback |
| 日志 | 全部走 `Logger`，禁止 `System.out.println` |

#### 从 `YamlConfigManager` 迁入的能力

- Icon Hash 规则 CRUD（`get/add/update/removeIconHashRule`）
- 导入合并（`mergeUpdate`，对应旧 `mergeUpdateYamlConfig`）
- 按索引更新/删除（UI 面板按 table row 操作，保留 index 语义）

> 注：旧 `YamlConfigLoader` 的 `updateRule/removeRule` 按 id 操作；UI 实际按 index 操作（`updateFingerprintRule(row, rule)` 等）。统一为 **按 index**（与 UI 一致），id 仅作数据字段保留。

#### 明确不迁入（死代码）

| 方法 | 原因 |
|------|------|
| `parseYamlString` | 全项目无调用 |
| `getIgnoreExtensions` | 全项目无调用；后缀过滤走 `Config.KEY_EXCLUDE_SUFFIX` |

#### `mergeUpdate` 必须修复的既有 bug

旧 `mergeUpdateYamlConfig` 合并后只写回：

```java
mergedData.put("Load_List", oldLoadList);
mergedData.put("Bypass_List", oldBypassList);
writeYamlConfig(mergedData);  // ← 整文件覆盖，Icon_Hash_List 被抹掉
```

新 `mergeUpdate` 约定：

1. 以 `readConfig()` 得到的**完整旧配置**为底（保留所有未知键，含 `Icon_Hash_List`）。
2. 仅合并更新 `Load_List` 与 `Bypass_List`（及若导入侧存在的 `Icon_Hash_List`，按 name+hash 去重追加）。
3. 写回完整 map，禁止只放两个 key 后覆盖。

#### 从 `YamlConfigLoader` 保留的能力

- mtime 缓存的 `readConfig / getEnabledRules / getScanPaths`
- `generateRecursivePaths`
- 平台默认路径

### 4.2 删除 `YamlConfigManager`

- 文件：`extender/src/main/java/burp/tdou/fingerscan/core/YamlConfigManager.java` **删除**
- 所有 import / 字段 / 构造参数改为 `YamlConfigStore`

### 4.3 `FingerScan` 构造改造

```java
// 旧
public FingerScan() { ... 内部 new YamlConfigLoader + YamlConfigManager ... }

// 新
public FingerScan(YamlConfigStore store) {
    ...
    mFingerprintPanel = new FingerprintPanel(store);
    // IconDataPanel 仍在 initIconDataPanel 中创建，同样注入 store
}

// 独立 main 测试入口
public static void main(String[] args) {
    Config.init(null);
    YamlConfigStore store = new YamlConfigStore(Config.get("yaml_config_path"));
    FingerScan fingerScan = new FingerScan(store);
    ...
}
```

### 4.4 `FingerprintPanel` / `IconDataPanel`

- 构造参数类型：`YamlConfigManager` → `YamlConfigStore`
- 方法调用映射：

| 旧（Manager） | 新（Store） |
|---|---|
| `getConfigFilePath()` | 同名 |
| `setConfigFilePath(p)` | 同名 |
| `getFingerprintRules()` | 同名 |
| `addFingerprintRule(r)` | `addRule(r)` |
| `updateFingerprintRule(i, r)` | `updateRule(i, r)` |
| `removeFingerprintRule(i)` | `removeRule(i)` |
| `getIconHashRules()` | 同名 |
| `addIconHashRule(r)` | 同名 |
| `updateIconHashRule(i, r)` | 同名 |
| `removeIconHashRule(i)` | 同名 |
| `readYamlConfig()` | `readConfig()` |
| `writeYamlConfig(d)` | `writeConfig(d)` |
| `mergeUpdateYamlConfig(d)` | `mergeUpdate(d)` |
| `new YamlConfigManager(importPath)` 临时实例 | `new YamlConfigStore(importPath)` 临时实例（仅导入/导出路径，不注册 listener） |

- **删除** `setOnReloadCallback`：不再需要 UI → BurpExtender 的手动回调；store 的 listener 替代它。
- `IconDataPanel.setOnRuleAddedCallback` 同理删除；写 store 后自动通知。

### 4.5 `BurpExtender` 改动

```java
// initNewArchitecture
mYamlConfigStore = new YamlConfigStore(Config.get("yaml_config_path"));
YamlRuleEngine yamlEngine = new YamlRuleEngine(mYamlConfigStore);
mIconHashRuleLoader = new IconHashRuleLoader(mYamlConfigStore);
mIconHashRuleLoader.loadRules();

// 一次性注册监听者（替代 281/296 两处手动 invalidate）
mYamlConfigStore.addChangeListener(() -> {
    mIconHashRuleLoader.invalidate();
    mIconHashRuleLoader.loadRules();
    // 若指纹面板已创建，刷新其 icon hash 表格显示
    if (mFingerScan != null && mFingerScan.getFingerprintPanel() != null) {
        mFingerScan.getFingerprintPanel().loadIconHashRules();
    }
});

// 构造 UI
mFingerScan = new FingerScan(mYamlConfigStore);

// 删除：
//   mFingerScan.getIconDataPanel().setOnRuleAddedCallback(...)
//   mFingerScan.getFingerprintPanel().setOnReloadCallback(...)
```

字段：`mYamlConfigLoader` → `mYamlConfigStore`。

### 4.6 `YamlRuleEngine` / `IconHashRuleLoader`

- 构造参数类型：`YamlConfigLoader` → `YamlConfigStore`
- 调用的读方法名不变（`getEnabledRules` / `getScanPaths` / `readConfig`），内部无需大改
- `IconHashRuleLoader` 保留自己的索引缓存 + `invalidate/loadRules`；由 store 的 listener 触发

---

## 5. 错误处理

| 场景 | 行为 |
|------|------|
| 配置文件不存在 | `readConfig()` 返回空 Map；`Logger.debug` 记录路径 |
| 解析失败 | 返回上次有效缓存（若有），否则空 Map；`Logger.error` |
| 写入失败 | `Logger.error`；**不**通知 listener（文件未变） |
| listener 抛异常 | 捕获、记日志、继续通知其余 listener |
| 内置 yaml 缺失 | 已有 `extractDefaultYamlConfig` 的 error 日志（本会话已加） |

---

## 6. 迁移步骤（实现顺序）

1. **新建** `YamlConfigStore`：以现有 `YamlConfigLoader` 为底，迁入 Manager 的 Icon Hash CRUD / mergeUpdate / 按 index 写接口，加入 listener 机制。
2. **改** `YamlRuleEngine`、`IconHashRuleLoader` 依赖类型为 `YamlConfigStore`。
3. **改** `FingerprintPanel`、`IconDataPanel` 依赖类型与方法映射；删除 `setOnReloadCallback` / `setOnRuleAddedCallback`。
4. **改** `FingerScan` 有参构造 + `main` 测试入口。
5. **改** `BurpExtender`：创建唯一 store、注入、注册 listener；删除两处手动 invalidate。
6. **删除** `YamlConfigManager.java`、`YamlConfigLoader.java`。
7. 编译验证；手动冒烟：改一条指纹规则 → 确认运行时立即生效、UI 表格刷新正常。

---

## 7. 风险与回退

| 风险 | 缓解 |
|------|------|
| UI 方法名映射遗漏 | 编译期暴露（类型删除后所有旧引用必挂） |
| listener 在 UI 线程做 reload 卡顿 | 实测 icon/指纹索引重建为毫秒级；若后续变慢再异步化 |
| `main` 测试入口忘改 | 编译期暴露 |
| 导入/导出临时 store 误注册 listener | 约定：临时实例不 `addChangeListener`；文档与代码注释标明 |

回退：git revert 本改动即可；不涉及数据迁移。

---

## 8. 验收标准

- [ ] 全项目只存在 **一个** `YamlConfigStore` 运行时实例（`BurpExtender` 持有并注入）
- [ ] `YamlConfigManager` / `YamlConfigLoader` 源文件已删除
- [ ] 代码中无 `System.out.println` 与 YAML 配置相关的调试输出
- [ ] `BurpExtender` 中无手动 `invalidateCache` / `setOnReloadCallback` / `setOnRuleAddedCallback`
- [ ] UI 增删改指纹规则后，无需点"重新加载"，运行时扫描即用新规则
- [ ] UI 增删改 Icon Hash 规则后，icon 匹配立即生效
- [ ] 切换配置文件路径后，UI 与运行时均读到新文件（`setConfigFilePath` 触发 listener）
- [ ] 导入合并后 `Icon_Hash_List` 不被抹掉（修复旧 `mergeUpdateYamlConfig` bug）
- [ ] `./gradlew build` / `mvn package` 成功，产物版本为 `v3.0.5`
- [ ] 独立 `FingerScan.main` 可启动（使用注入的 store）

---

## 9. 已完成的前置修复（本会话）

以下不在本 spec 实现范围内，但已落地，作为本改动的前置：

1. `build.gradle` 版本 `3.0.4 → 3.0.5`
2. `Config_yaml.yaml` 从 `src/main/java/yaml/` 移至 `src/main/resources/`（修复打包缺失）
3. `extractDefaultYamlConfig` 在资源缺失时打 `Logger.error` 告警
