<div align="center">
<h1>FingerScan</h1>
<p>基于 BurpSuite 的自动化指纹识别与递归目录扫描插件</p>
</div>

## 简介

FingerScan 是一款 BurpSuite 扩展插件，用于被动/主动 Web 指纹识别、递归目录扫描和 Favicon Hash 收集。基于 Montoya API 开发。

核心能力：

- **被动指纹识别** — 代理流量自动匹配 YAML 规则库中的指纹特征
- **Favicon Hash 识别** — 自动采集网站图标并计算 MurmurHash3/MD5，匹配已知应用指纹（兼容 Shodan/FOFA 格式）
- **递归目录扫描** — 基于 URL 路径层级 × 规则路径列表组合扫描
- **Payload 处理** — 对请求进行自定义变换后重放
- **路径收集** — 自动提取代理流量中的一级路径，可导出为字典

## 功能模块

### 数据看板

主扫描结果面板，展示指纹匹配结果，支持请求/响应查看。

工具栏开关：

| 开关 | 说明 |
|------|------|
| Listen Proxy | 开启/关闭代理流量监听 |
| Remove Header | 移除请求中指定 Header |
| Replace Header | 替换请求 Header（从字典加载） |
| Payload Processing | 启用 Payload 变换处理 |
| Active Scan | 启用主动扫描（递归目录 + Payload） |

右键菜单支持：复制 URL、发送到 Repeater、计算 Body MD5/Hash、添加 Host 黑名单、临时过滤等。

### 指纹管理

管理 YAML 格式的指纹规则，包含两个子面板：

- **正则规则** — 基于 URL + 正则表达式匹配响应体的指纹规则，支持增删改查、导入导出、批量启用/禁用
- **Icon Hash 规则** — 基于 MurmurHash3 / MD5 匹配 Favicon 的应用指纹规则，内置 500+ 条常见应用指纹

### 图标数据

自动采集网站 Favicon 图标并展示：

- 图标预览（支持 PNG/GIF/JPEG/ICO 格式）
- MurmurHash3 和 MD5 哈希值
- 来源站点列表
- 备注编辑（双击备注列直接输入）
- 一键转为指纹规则
- 导出原始图标文件

Favicon 检测机制：
1. HTML 响应经过代理时，自动解析 `<link rel="icon">` / `<link rel="shortcut icon">` / `<link rel="apple-touch-icon">` / `<link rel="mask-icon">` 标签
2. 注册 Favicon URL 到内存注册表
3. 浏览器后续请求匹配的图标 URL 时自动采集计算 Hash
4. `/favicon.ico` 默认路径始终采集

### 路径收集

自动提取代理流量中的一级 URL 路径（如 `/api`、`/admin`），统计命中次数，支持：

- 按关键词搜索过滤
- 导出为 `.txt` 字典文件（可用作目录扫描字典）
- 按命中次数排序

### 配置

#### Payload 处理规则

定义请求变换规则组，支持 4 种规则类型：

| 类型 | 说明 |
|------|------|
| Add Prefix | 在目标作用域前添加前缀 |
| Add Suffix | 在目标作用域后添加后缀 |
| Match Replace | 正则查找替换 |
| Condition Check | 条件断言（不满足则中止） |

每条规则支持 4 种作用域：URL、Header、Body、整个请求。

规则组支持 **Merge 模式**（多组规则合并为一个请求）和**独立模式**（每组单独生成请求）。

#### 请求设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| QPS 限制 | 1024 | 每秒最大请求数 |
| 请求延迟 | 0ms | 请求间隔时间 |
| 重试次数 | 3 | 请求失败重试次数 |
| 重试间隔 | 3000ms | 重试间隔时间 |
| 扫描层级 | 99 | 递归目录扫描深度 |
| 扫描方向 | 从左到右 | 路径遍历方向 |
| 包含方法 | GET\|POST | 仅处理指定 HTTP 方法 |
| 排除后缀 | css, js, png, jpg... | 跳过指定后缀的请求 |

#### Host 设置

| 配置项 | 说明 |
|--------|------|
| Host 白名单 | 仅扫描列表中的 Host（支持通配符 `*`） |
| Host 黑名单 | 排除列表中的 Host |
| 超时主机拦截 | 自动跳过响应超时的主机 |

#### 重定向设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| 跟随重定向 | 开启 | 自动跟随 30x 重定向 |
| Cookie 跟随 | 开启 | 重定向时携带 Set-Cookie |
| 限制目标 Host | 开启 | 仅跟随相同域名的重定向 |

### 动态变量

Header 模板和 Payload 规则中支持以下动态变量：

| 变量 | 说明 |
|------|------|
| `{{host}}` | 目标主机（含非标准端口） |
| `{{protocol}}` | 协议（http/https） |
| `{{ip}}` | 目标 IP |
| `{{domain}}` | 完整域名 |
| `{{domain.main}}` | 主域名（如 example.com） |
| `{{subdomain}}` | 子域名 |
| `{{webroot}}` | 第一级路径段 |
| `{{random.ip}}` | 随机公网 IP |
| `{{random.local-ip}}` | 随机内网 IP |
| `{{random.ua}}` | 随机 User-Agent |
| `{{timestamp}}` | Unix 时间戳 |
| `{{date.yyyy}}` / `{{date.MM}}` / `{{date.dd}}` | 日期 |
| `{{time.HH}}` / `{{time.mm}}` / `{{time.ss}}` | 时间 |

## 指纹规则格式

### 正则规则（Load_List）

```yaml
Load_List:
  - id: 1
    loaded: true
    name: Spring Actuator
    type: Spring
    method: GET
    url: /actuator
    state: '200'
    re: actuator|endpoints
    info: Spring Actuator Exposed
```

| 字段 | 说明 |
|------|------|
| `loaded` | 是否启用 |
| `name` | 规则名称 |
| `type` | 分类（Spring, ApiDoc, CMS 等） |
| `method` | HTTP 方法 |
| `url` | 探测路径 |
| `state` | 期望状态码 |
| `re` | 响应体匹配正则 |
| `info` | 匹配后的提示信息 |

### Icon Hash 规则（Icon_Hash_List）

```yaml
Icon_Hash_List:
  - name: Jenkins
    murmur_hash: "81586312"
    md5: ""
    type: Application
    info: Jenkins CI
```

`murmur_hash` 和 `md5` 至少填写一个。Hash 值与 Shodan/FOFA 的 Favicon Hash 格式兼容。

## 编译构建

**环境要求：**

- JDK 17+
- Apache Maven 3.9+

**编译命令：**

```bash
mvn clean package
```

**输出文件：**

```
extender/target/FingerScan-v3.0.1.jar
```

## 安装使用

1. 编译或下载 `FingerScan-v3.0.1.jar`
2. 打开 Burp Suite → Extensions → Add
3. Extension Type 选择 `Java`
4. 选择 JAR 文件，点击 Next
5. 插件加载后，在顶部标签栏会出现 `FingerScan` 标签

**工作目录：** 插件会在 JAR 所在目录下自动创建 `FingerScan/` 文件夹，包含：

```
FingerScan/
├── config.json          # 插件配置
├── icon_hash.db         # Favicon 数据库（SQLite）
├── wordlist/            # 字典目录
│   ├── headers/         # 请求头模板
│   ├── payload/         # 扫描路径字典
│   ├── user-agent/      # UA 池
│   ├── host-allowlist/  # Host 白名单
│   ├── host-blocklist/  # Host 黑名单
│   └── remove-headers/  # 需移除的 Header
└── collect/             # 收集数据目录
```

## 快速开始

1. **开启代理监听** — 在数据看板勾选 `Listen Proxy`
2. **配置 Host 白名单**（推荐） — 在 Host 配置中添加目标域名，避免扫描无关站点
3. **浏览目标网站** — 正常浏览，插件自动进行被动指纹匹配和 Favicon 采集
4. **查看结果** — 数据看板显示指纹匹配结果，图标数据面板显示采集的 Favicon
5. **主动扫描** — 勾选 `Active Scan`，插件会对经过代理的 URL 进行递归目录扫描

## 技术架构

```
BurpExtender (Montoya API)
    ├── ProxyResponseHandler → submitToOrchestrator()
    └── ContextMenuItemsProvider → "Send to FingerScan"

ScanOrchestrator
    ├── FilterChain (MethodFilter → HostFilter → SuffixFilter)
    └── ScanStrategy[]
        ├── PassiveFingerprintStrategy   (被动指纹匹配)
        ├── IconHashStrategy             (Favicon 采集 + Hash)
        ├── RecursiveDirectoryScanStrategy (递归目录扫描)
        └── PayloadProcessingStrategy    (Payload 变换)

RequestPipeline
    ├── RequestPool (50 threads)   → HTTP 请求 + 重试
    ├── AnalysisPool (10 threads)  → 指纹匹配 + Hash 计算
    ├── DeduplicateFilter          → URL 去重
    ├── QpsLimiter                 → 限速
    └── ResultDispatcher           → 结果分发

Storage
    ├── config.json     (插件配置)
    ├── Config_yaml.yaml (指纹规则)
    └── icon_hash.db    (SQLite: icons + icon_sources + collected_paths)
```

## 致谢

本项目基于 [OneScan](https://github.com/vaycore/OneScan) 二次开发，感谢原作者 vaycore 的贡献。

## License

本项目仅供安全研究和授权测试使用。
