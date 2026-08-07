<div align="center">
<h1>FingerScan</h1>
<p>基于 BurpSuite 的自动化指纹识别与递归目录扫描插件</p>
<p><b>v3.0.11</b></p>
</div>

## 简介

FingerScan 是一款 BurpSuite 扩展插件，用于被动/主动 Web 指纹识别、递归目录扫描和 Favicon Hash 收集。基于 **Montoya API** 开发。

核心能力：

- **被动指纹识别** — 代理流量自动匹配 YAML 规则库（正则 / 字符串关键字）
- **Favicon Hash 识别** — 解析 HTML 中的 icon 链接并**主动拉取**同 host 图标，计算 MurmurHash3/MD5（兼容 Shodan/FOFA）
- **递归目录扫描** — URL 路径层级 × 规则路径列表组合扫描
- **Payload 处理** — 对请求做自定义变换后重放
- **路径收集** — 提取代理流量一级路径，可导出为字典
- **匹配性能优化** — 字面量预过滤、非文本门禁、有界分析池、单规则匹配超时、危险前瞻正则优化

## 下载

- [Releases](https://github.com/TD0U/FingerScan/releases)（推荐下载最新 `FingerScan-v*.jar`）
- 当前最新： [v3.0.11](https://github.com/TD0U/FingerScan/releases/tag/v3.0.11)

## 功能模块

### 数据看板

主扫描结果面板，展示**指纹命中**记录，支持请求/响应查看。

工具栏开关：

| 开关 | 说明 |
|------|------|
| Listen Proxy | 开启/关闭代理流量监听 |
| Remove Header | 移除请求中指定 Header |
| Replace Header | 替换请求 Header（从字典加载） |
| Payload Processing | 启用 Payload 变换处理 |
| Active Scan | 启用主动扫描（递归目录 + Payload） |

说明：

- 同一 **主机 + 指纹** 在数据看板只保留首条，避免递归扫描下同指纹刷屏；完整路径历史见「扫描记录」。
- 右键：复制 URL、发送到 Repeater、计算 Body MD5/Hash、添加 Host 黑名单、临时过滤等。

### 指纹管理

管理 YAML 指纹规则，包含两个子面板：

- **正则 / 字符串规则** — 匹配响应体的指纹规则，支持增删改查、导入导出、批量启用/禁用  
  - **匹配方式 `match`**：`regex`（默认）或 `keyword`（字符串）
- **Icon Hash 规则** — MurmurHash3 / MD5 匹配 Favicon，内置大量常见应用指纹

### 图标数据

自动采集网站 Favicon 并展示：

- 图标预览（PNG/GIF/JPEG/ICO）
- MurmurHash3 / MD5、来源站点、备注
- 一键转为指纹规则、导出原始图标

Favicon 采集机制：

1. HTML 响应进入插件时，解析 `<link rel="icon|shortcut icon|apple-touch-icon|mask-icon">`
2. 注册路径到内存注册表，并对**同 host** 声明路径**主动 GET** 一次（不依赖浏览器请求顺序）
3. 带 query 的 icon 地址会保留 query（如动态 favicon 接口）
4. `/favicon.ico` 默认路径仍可被动采集
5. 跨域 icon 只注册不主动发包

### 路径收集

提取代理流量一级 URL 路径（如 `/api`、`/admin`），统计命中次数，支持搜索、导出字典、按命中排序。

### 配置

#### Payload 处理规则

| 类型 | 说明 |
|------|------|
| Add Prefix / Suffix | 在作用域前后添加内容 |
| Match Replace | 正则查找替换 |
| Condition Check | 条件断言（不满足则中止） |

作用域：URL、Header、Body、整个请求。支持 Merge / 独立模式。

#### 请求设置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| QPS 限制 | 1024 | 每秒最大请求数 |
| 请求延迟 | 0ms | 请求间隔 |
| 重试次数 / 间隔 | 3 / 3000ms | 失败重试 |
| 扫描层级 / 方向 | 99 / 从左到右 | 递归目录深度与方向 |
| 包含方法 | GET\|POST | 仅处理指定方法 |
| 排除后缀 | css, png, jpg, ico... | 后缀过滤（favicon 已注册路径可放行） |

#### Host / 重定向

| 配置项 | 说明 |
|--------|------|
| Host 白/黑名单 | 支持通配符 `*` |
| 超时主机拦截 | 超时主机后续任务丢弃 |
| 跟随重定向 / Cookie 跟随 | 30x 与 Set-Cookie 行为 |
| 限制目标 Host | 重定向是否受名单约束 |

#### 其他（匹配性能相关）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| 指纹 URL 匹配 | 关 | 开启后规则 `url` 非空且非 `/` 时需与请求路径精确匹配 |
| 字面量预过滤 | 开 | 用必现字面量跳过不可能命中的正则（失败开放，不截断正文） |
| 跳过非文本响应 | 开 | 图片/压缩包等不跑 YAML 指纹；空 body 始终跳过 |
| 字面量最短长度 | 3 | 短于此长度的字面量不参与预过滤 |
| 分析线程数 | 4 | 被动分析池线程数 |
| 分析队列容量 | 2000 | 有界队列；满则丢弃并计数 |
| 主动匹配并发 | 4 | 请求线程上同步 YAML 匹配信号量（阻塞不丢结果） |
| 单条规则匹配超时 | 100ms | `Matcher.find` 超时；`0`=关闭；打断灾难性回溯 |

### 动态变量

Header / Payload 中可用：`{{host}}`、`{{protocol}}`、`{{ip}}`、`{{domain}}`、`{{domain.main}}`、`{{subdomain}}`、`{{webroot}}`、`{{random.ip}}`、`{{random.local-ip}}`、`{{random.ua}}`、`{{timestamp}}`、`{{date.*}}`、`{{time.*}}` 等。

## 指纹规则格式

### 规则字段（Load_List）

```yaml
Load_List:
  # 正则（默认，兼容旧配置）
  - id: 1
    loaded: true
    name: Spring Actuator
    type: Spring
    method: GET
    url: /actuator
    state: '200'
    match: regex          # 可省略，缺省即 regex
    re: actuator|endpoints
    info: Spring Actuator Exposed

  # 字符串关键字（推荐用于多关键字 AND，避免 (?=.*a)(?=.*b)）
  - id: 2
    loaded: true
    name: Doc File
    type: ApiDoc
    method: GET
    url: /
    state: '200'
    match: keyword
    re: swagger && webjars
    info: Swagger UI with webjars
```

| 字段 | 说明 |
|------|------|
| `loaded` | 是否启用 |
| `name` | 规则名称 |
| `type` | 分类 |
| `method` | HTTP 方法 |
| `url` | 探测路径 / URL 精确匹配用路径 |
| `state` | 期望状态码；`0` 或空表示不限 |
| `match` | `regex`（默认）或 `keyword` |
| `re` | **regex**：正则；**keyword**：关键字表达式 |
| `info` | 命中提示 |

**keyword 表达式：**

| 写法 | 含义 |
|------|------|
| `swagger` | 正文包含该串（忽略大小写） |
| `a && b` | 同时包含 a 与 b（AND） |
| `a \|\| b` | 包含 a 或 b（OR） |

同一规则中 **不要** 混用 `&&` 与 `||`。

引擎还会对纯 `(?=.*字面量)(?=.*字面量)` 形式的正则做 contains-AND 优化；新规则仍建议直接使用 `match: keyword`。

### Icon Hash 规则（Icon_Hash_List）

```yaml
Icon_Hash_List:
  - name: Jenkins
    murmur_hash: "81586312"
    md5: ""
    type: Application
    info: Jenkins CI
```

`murmur_hash` 与 `md5` 至少填一个，格式兼容 Shodan/FOFA Favicon Hash。

## 编译构建

**环境：** JDK 17+、Maven 3.9+

```bash
mvn clean package
```

**产物：**

```text
extender/target/FingerScan-v3.0.11.jar
```

（仅 Maven 构建；CI 仅在推送 `v*` tag 时构建并发布 Release。）

## 安装使用

1. 从 [Releases](https://github.com/TD0U/FingerScan/releases) 下载或本地编译 JAR  
2. Burp Suite → Extensions → Add → Type: **Java** → 选择 JAR  
3. 顶部出现 **FingerScan** 标签  

**工作目录**（JAR 同级下的 `FingerScan/`）：

```text
FingerScan/
├── config.json           # 插件配置
├── Config_yaml.yaml      # 指纹规则（首次从内置释放）
├── icon_hash.db          # Favicon / 路径 SQLite
├── wordlist/             # headers、payload、UA、黑白名单等
└── collect/              # 收集数据
```

## 快速开始

1. 数据看板勾选 **Listen Proxy**  
2. （推荐）Host 配置中设置白名单  
3. 浏览目标站 → 被动指纹 + Favicon 主动采集  
4. 数据看板查看命中；图标数据查看 Favicon  
5. 需要目录探测时勾选 **Active Scan**  
6. 多关键字指纹请用 **字符串匹配**（`match: keyword`），避免复杂前瞻正则  

## 技术架构（简图）

```text
BurpExtender (Montoya API)
    ├── ProxyResponseHandler → ScanOrchestrator
    └── ContextMenu → Send to FingerScan

ScanOrchestrator
    ├── FilterChain (Method → Host → Suffix[+FaviconRegistry])
    └── Strategies
        ├── PassiveFingerprint
        ├── IconHash（解析 link + 主动拉取同 host favicon）
        ├── RecursiveDirectory
        └── PayloadProcessing

RequestPipeline
    ├── requestPool (50) + QPS + 重试
    ├── analysisPool (默认 4，有界队列) → 被动分析 / IconHash
    ├── match 信号量（默认 4）→ 主动路径 YAML 匹配限流
    ├── DeduplicateFilter / ResultDispatcher
    └── YamlRuleEngine
            ├── keyword → contains AND/OR
            ├── regex → 字面量预过滤 + find + 单规则超时
            └── (?=.*a)(?=.*b) → contains AND 优化
```

## 版本与更新日志

详见仓库根目录 [CHANGELOG.md](./CHANGELOG.md)。

近期版本要点：

| 版本 | 要点 |
|------|------|
| 3.0.11 | 字符串匹配 `match: keyword`（`&&` / `\|\|`） |
| 3.0.10 | 单规则 match 超时；双前瞻正则 contains 优化 |
| 3.0.9 | 字面量预过滤、非文本门禁、有界分析池 |
| 3.0.8 | Favicon 主动拉取；icon URL 保留 query |
| 3.0.7 | 数据看板主机+指纹去重；报文去重引用 |

## 致谢

本项目基于 [OneScan](https://github.com/vaycore/OneScan) 二次开发，感谢原作者 vaycore。

## License

本项目仅供安全研究和**授权**测试使用。
