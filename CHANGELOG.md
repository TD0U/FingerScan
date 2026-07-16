# 更新日志

## 2026-07-16 (v3.0.6)

### 优化

- **指纹 URL 精确匹配开关**

  被动指纹识别默认对任意路径的响应均跑正则匹配，会产生大量无意义的误匹配（如 `/druid/login.html` 命中 Swagger 规则）。

  新增开关「指纹 URL 匹配」（默认关闭），开启后规则的 `url` 字段不为空且不为 `"/"` 时，要求请求路径与规则 `url` 精确匹配才进行正则匹配；`url` 为空或 `"/"` 的规则不受影响，始终匹配。路径匹配自动清除查询参数和锚点，兼容完整 URL 格式。

  涉及文件：
  - `Config.java` — 新增 `KEY_FINGERPRINT_URL_MATCH`，默认 `false`
  - `RuleEngine.java` — 接口新增 `match(req, resp, requestPath)` 签名，旧签名保留为 default 方法兼容
  - `YamlRuleEngine.java` — 实现新签名，新增 `matchPath()` 方法
  - `CompositeRuleEngine.java` — 透传 `requestPath`
  - `RequestPipeline.java` — 两处 `match()` 补传请求路径，`extractRequestPath()` 优先使用 Montoya `request.path()`
  - `RequestTab.java` — 新增开关 UI
  - `messages_zh_CN.properties` / `messages_en_US.properties` — 开关文案

### 架构重构

- **YAML 配置统一为单一数据源 `YamlConfigStore`**

  原先同一份 `Config_yaml.yaml` 被 `YamlConfigLoader`（运行时）与 `YamlConfigManager`（UI）两套实现分别持有，各自缓存、互不通知，UI 改规则后运行时常靠 mtime 巧合才刷新；`BurpExtender` 里还需要人肉 `invalidateCache()` 打补丁。

  **改动**：
  - 新增 `YamlConfigStore`：读（mtime 缓存）+ 写（index 级 CRUD）+ `mergeUpdate` + 变更监听，全局唯一
  - `BurpExtender` 创建并注入到 `FingerScan`、`YamlRuleEngine`、`IconHashRuleLoader` 与 UI 面板
  - 写盘成功后自动 `notifyListeners`，`IconHashRuleLoader` 索引与图标规则表自动重建
  - 删除 `YamlConfigLoader`、`YamlConfigManager` 及 `setOnReloadCallback` / `setOnRuleAddedCallback`

  涉及文件：
  - `YamlConfigStore.java`（新增）
  - `BurpExtender.java`、`FingerScan.java`
  - `YamlRuleEngine.java`、`IconHashRuleLoader.java`
  - `FingerprintPanel.java`、`IconDataPanel.java`
  - 删除 `YamlConfigLoader.java`、`YamlConfigManager.java`

### Bug 修复

- **导入指纹规则会抹掉 `Icon_Hash_List`**

  旧 `mergeUpdateYamlConfig` 合并后只写回 `Load_List` 与 `Bypass_List`，整文件覆盖导致已有 Icon Hash 规则丢失。

  **修复**：`YamlConfigStore.mergeUpdate` 以完整旧配置为底合并，保留 `Icon_Hash_List` 及未知键；导入侧若含图标规则则按 name+hash 去重追加。

- **Gradle 产物版本与插件显示不一致 / 内置指纹库未打进 jar**

  `build.gradle` 仍为 `3.0.4`，而 `Constants`/`pom`/CHANGELOG 已是 `3.0.5`；`Config_yaml.yaml` 放在 `src/main/java/yaml/` 未进入 Gradle resources，首次安装无法释放默认指纹库。

  **修复**：
  - 版本号统一（本版起为 `3.0.6`：`Constants` / `build.gradle` / `extender/pom.xml`）
  - `Config_yaml.yaml` 移至 `src/main/resources/`
  - 内置 yaml 缺失时打 `Logger.error` 告警，不再静默

## 2026-06-04 (v3.0.5)

### Bug 修复

- **图标预览无法解析 8bpp/4bpp ICO 文件**

  `parseDib()` 只处理了 32bpp 和 24bpp 格式，8bpp（256色调色板）和 4bpp（16色调色板）的 ICO 文件（如 ZKECO16.ico）解析返回 null，界面显示"无法解析图标"。

  **修复**：新增 `parseDibPalette()` 方法，读取 DIB 头后的调色板并按索引映射像素，正确处理 4 字节行对齐。

  涉及文件：
  - `IconDataPanel.java` — `parseDib()` 增加 8bpp/4bpp 分支，新增 `parseDibPalette()` 方法

### 优化

- **图标来源表格支持右键复制 URL**

  来源站点表格只存储 host 和 path，无法直接复制完整 URL。新增右键菜单"复制 URL"，自动拼接 host + path 为完整 URL 复制到剪贴板。

  涉及文件：
  - `IconDataPanel.java` — 来源表格添加右键弹出菜单

- **路径收集面板支持框选单元格复制**

  原来只能多选整行，无法单独框选路径列。改为单元格级别选择模式（`setCellSelectionEnabled`），支持鼠标拖选任意单元格区域，Ctrl+C 或右键"复制选中内容"复制选中单元格，多列时 Tab 分隔，多行时换行分隔。

  涉及文件：
  - `PathCollectPanel.java` — 改为单元格选择模式，新增 `copySelectedCells()` 方法

## 2026-05-22 (v3.0.4)

### Bug 修复

- **Repeater/Intruder 中右键「Send to FingerScan」无效**

  在 Repeater、Intruder 等消息编辑器中右键发送时，`event.selectedRequestResponses()` 返回空列表（该方法仅适用于 Proxy History、Site Map 等列表视图），导致右键菜单点击后没有任何请求被提交到扫描流程。

  **修复**：当 `selectedRequestResponses()` 为空时，回退到 `event.messageEditorRequestResponse()` 获取当前编辑器中的请求响应对。

  涉及文件：
  - `BurpExtender.java` — 右键菜单 ActionListener 增加 `messageEditorRequestResponse()` 回退逻辑

## 2026-05-09 (v3.0.3)

### Bug 修复

- **指纹匹配未校验 HTTP 状态码，302 响应误匹配 state=200 的规则**

  `YamlRuleEngine.match()` 完全忽略了规则的 `state` 字段，只要正则匹配就算命中。导致 302 响应中 Location 头包含关键词（如 "druid"）时，`state: '200'` 的规则也会被匹配，间接触发重定向循环。

  **修复**：在 `match()` 中从响应首行解析 HTTP 状态码，与规则 `state` 字段比对。`state` 为 `0` 或空时忽略状态码条件，其他值严格匹配。

  涉及文件：
  - `YamlRuleEngine.java` — 新增 `parseStatusCode()` 和 `matchStatusCode()` 方法

### 优化

- **指纹规则分组按钮栏**

  在指纹管理的正则规则面板顶部新增分组按钮栏，按规则的 `type` 字段自动分组。按钮显示分组名称和数量（如 `Spring (6)`），使用 `JToggleButton` + `ButtonGroup` 实现，点击即时切换无延迟。分组过滤与搜索可叠加使用，批量启用/禁用操作仅作用于当前可见的规则，操作后保持当前分组不跳转。

  涉及文件：
  - `FingerprintPanel.java` — 新增分组按钮栏、`buildGroupTabs()`、`applyGroupFilter()`、`applyFilters()`，重构 `batchEnable()` 和搜索过滤逻辑

- **指纹规则表格支持多选**

  选择模式从 `SINGLE_SELECTION` 改为 `MULTIPLE_INTERVAL_SELECTION`，支持 Ctrl/Shift 多选操作。

  涉及文件：
  - `FingerprintPanel.java` — 表格选择模式变更

- **分组内操作后保持当前分组**

  删除、编辑等操作触发规则重载后，分组不再跳回"全部"，而是保持在当前选中的分组。

  涉及文件：
  - `FingerprintPanel.java` — `buildGroupTabs()` 重建前记录当前分组名，重建后恢复

- **指纹规则编辑器 "状态" 改为 "状态码"**

  原编辑器的 "状态" 下拉框选项为 `active/inactive/testing/deprecated`（生命周期状态），与实际 YAML 中存储的 HTTP 状态码（`200`、`206`）不一致。改为 `0`（忽略状态码）和 `200`，支持手动输入其他状态码。

  涉及文件：
  - `FingerprintRuleDialog.java` — 标签改为 "状态码:"，下拉选项改为 `{0, 200}`
  - `FingerprintPanel.java` — 表格列头 "状态" 改为 "状态码"

## 2026-05-08 (v3.0.2)

### Bug 修复

- **重定向请求触发递归目录扫描导致无限循环**

  当指纹规则（如 Druid Monitor）的 URL 路径（`/druid/login.html`）与目标服务器返回 302 重定向时，重定向请求会重新进入 `ScanOrchestrator`，`RecursiveDirectoryScanStrategy` 基于重定向路径再次生成递归扫描任务，导致路径不断叠加（`/druid/druid/druid/...`），形成无限循环。

  **修复**：在 `RecursiveDirectoryScanStrategy` 和 `PayloadProcessingStrategy` 的 `shouldApply()` 中增加 `request.isFromRedirect()` 检查，重定向请求仅做被动指纹识别，不再触发递归目录扫描和 Payload 处理。

  涉及文件：
  - `RecursiveDirectoryScanStrategy.java` — `shouldApply()` 跳过重定向请求
  - `PayloadProcessingStrategy.java` — `shouldApply()` 跳过重定向请求

## 2026-05-06 (v3.0.1)

### 优化

- **路径收集命中次数改为按来源主机计数**

  原先命中次数为所有请求的累计总次数（`SUM(hit_count)`），同一主机的重复请求会导致计数膨胀，不能直观反映路径的分布广度。

  **优化**：将 `PathStore.getAllPaths()` 的聚合方式从 `SUM(hit_count)` 改为 `COUNT(DISTINCT host)`，命中次数现在表示有多少个不同的来源主机访问了该路径。

  涉及文件：
  - `PathStore.java` — `getAllPaths()` 查询聚合方式变更

## 2026-04-24

### Bug 修复

- **图标转为指纹规则后运行时规则未刷新**

  在「图标数据」面板点击「转为指纹规则」后，新规则仅写入 YAML 文件，`YamlConfigLoader` 缓存和 `IconHashRuleLoader` 内存索引未被刷新，导致后续新流量的 favicon 匹配仍使用旧规则，需手动到「指纹管理」点击「重新加载」才能生效。

  **修复**：在 `IconDataPanel` 添加 `onRuleAddedCallback` 回调，由 `BurpExtender` 注册，添加规则后自动执行 `YamlConfigLoader.invalidateCache()` + `IconHashRuleLoader.invalidate()` + `loadRules()`，同时刷新「指纹管理」面板的 Icon Hash 规则表格。

  涉及文件：
  - `IconDataPanel.java` — 新增回调字段与 setter，`convertToRule()` 中触发回调
  - `BurpExtender.java` — 注册回调，串联缓存失效与规则重载
  - `FingerprintPanel.java` — `loadIconHashRules()` 可见性从 `private` 改为 `public`

- **图标转为指纹规则后「匹配结果」列未更新**

  转为规则后，图标数据表中该行的「匹配结果」列仍为空或旧值，因为 `IconHashStore.saveIcon()` 使用 `INSERT OR IGNORE`，已存在的图标行不会被更新。

  **修复**：在 `IconHashStore` 新增 `updateMatchResult()` 方法；在 `IconDataPanel.convertToRule()` 中添加规则后，将规则名称写入数据库并立即刷新表格对应行的「匹配结果」列。若已有匹配结果则追加拼接。

  涉及文件：
  - `IconHashStore.java` — 新增 `updateMatchResult(String murmurHash, String matchResult)`
  - `IconDataPanel.java` — `convertToRule()` 中更新数据库与 UI 表格
