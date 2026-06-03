<div align="center">

# FingerScan

**BurpSuite 被动指纹识别 / Favicon Hash / 递归目录扫描 / 路径收集 一体化插件**

[![Build](https://github.com/TD0U/FingerScan/actions/workflows/build.yml/badge.svg)](https://github.com/TD0U/FingerScan/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/TD0U/FingerScan?color=blue)](https://github.com/TD0U/FingerScan/releases/latest)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange)](https://adoptium.net/)
[![License](https://img.shields.io/github/license/TD0U/FingerScan)](LICENSE)

基于 Montoya API 开发 &bull; 支持 BurpSuite 2023.12+

</div>

---

## 功能概览

| 模块 | 说明 |
|------|------|
| **被动指纹识别** | 代理流量自动匹配 YAML 规则库，识别 Spring、Swagger、Nacos、Jenkins 等；支持路径精确匹配 |
| **Favicon Hash** | 自动采集网站图标，计算 MurmurHash3 / MD5，匹配已知应用指纹（兼容 Quake / FOFA） |
| **全局响应过滤** | 自定义过滤正则，响应体命中时自动跳过指纹识别（拦截伪 200 响应、重放攻击告警等） |
| **递归目录扫描** | 基于 URL 路径层级 x 规则路径列表组合扫描 |
| **Payload 处理** | 对请求进行自定义变换（前缀/后缀/正则替换/条件断言）后重放 |
| **路径收集** | 自动提取代理流量中的一级路径，统计命中主机数，可导出为字典 |

## 快速开始

### 安装

1. 前往 [Releases](https://github.com/TD0U/FingerScan/releases/latest) 下载 `FingerScan-v*.jar`
2. Burp Suite → Extensions → Add → Extension Type: `Java` → 选择 JAR 文件
3. 顶部标签栏出现 `FingerScan` 即安装成功

### 使用

1. 在数据看板勾选 **Listen Proxy** 开启代理监听
2. _(推荐)_ 在配置 → Host 设置中添加目标域名白名单
3. 正常浏览目标网站，插件自动进行被动指纹匹配和 Favicon 采集
4. 数据看板查看指纹匹配结果，图标数据面板查看采集的 Favicon
5. 勾选 **Active Scan** 可启用递归目录扫描

## 功能详解

### 数据看板

主扫描结果面板，展示指纹匹配结果，支持请求/响应查看。

**工具栏开关：**

| 开关 | 说明 |
|------|------|
| Listen Proxy | 开启/关闭代理流量监听 |
| Remove Header | 移除请求中指定 Header |
| Replace Header | 替换请求 Header（从字典加载） |
| Payload Processing | 启用 Payload 变换处理 |
| Active Scan | 启用主动扫描（递归目录 + Payload） |

右键菜单支持：复制 URL、发送到 Repeater、计算 Body MD5/Hash、添加 Host 黑名单、临时过滤等。
<img width="1641" height="757" alt="image" src="https://github.com/user-attachments/assets/f4ae0018-4924-4771-986f-2232ad62be19" />


### 指纹管理

管理 YAML 格式的指纹规则，包含三个子面板：

- **正则规则** — 基于 URL + 正则表达式匹配响应体，支持增删改查、导入导出、批量启用/禁用。被动匹配时要求请求路径与规则 `url` 字段一致（`url` 为 `/` 时仅匹配响应内容）
- **Icon Hash 规则** — 基于 MurmurHash3 / MD5 匹配 Favicon
- **过滤规则** — 全局响应过滤器，正则命中响应体时跳过所有指纹识别（用于拦截"伪 200"响应、重放攻击告警等），支持增删改查
<img width="1607" height="758" alt="image" src="https://github.com/user-attachments/assets/9c147ad6-746e-404a-b391-171dbf960ab4" />

### 图标数据

自动采集网站 Favicon 图标：

- 图标预览（PNG / GIF / JPEG / ICO）
- MurmurHash3 和 MD5 哈希值
- 来源站点列表 & 备注编辑
- 一键转为指纹规则 & 导出原始图标文件

<img width="3274" height="1500" alt="image" src="https://github.com/user-attachments/assets/e4bbd7ab-ad5f-4748-8f35-c35e511e8d2f" />


**Favicon 检测机制：**
1. HTML 响应经过代理时，自动解析 `<link rel="icon">` / `<link rel="shortcut icon">` 等标签
2. 注册 Favicon URL 到内存注册表
3. 浏览器后续请求匹配图标 URL 时自动采集计算 Hash
4. `/favicon.ico` 默认路径始终采集

### 路径收集

自动提取代理流量中的一级 URL 路径（如 `/api`、`/admin`），按命中主机数统计，支持搜索过滤和导出为 `.txt` 字典。

<img width="1643" height="743" alt="image" src="https://github.com/user-attachments/assets/cbf42f07-f4a7-41a3-9473-7a2891fa4ef9" />

### 配置

<details>
<summary><b>Payload 处理规则</b></summary>

| 类型 | 说明 |
|------|------|
| Add Prefix | 在目标作用域前添加前缀 |
| Add Suffix | 在目标作用域后添加后缀 |
| Match Replace | 正则查找替换 |
| Condition Check | 条件断言（不满足则中止） |

每条规则支持 4 种作用域：URL、Header、Body、整个请求。
规则组支持 **Merge 模式**（合并为一个请求）和**独立模式**（每组单独生成请求）。

</details>

<details>
<summary><b>请求设置</b></summary>

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

</details>

<details>
<summary><b>Host & 重定向设置</b></summary>

**Host 设置：**

| 配置项 | 说明 |
|--------|------|
| Host 白名单 | 仅扫描列表中的 Host（支持通配符 `*`） |
| Host 黑名单 | 排除列表中的 Host |
| 超时主机拦截 | 自动跳过响应超时的主机 |

**重定向设置：**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| 跟随重定向 | 开启 | 自动跟随 30x 重定向 |
| Cookie 跟随 | 开启 | 重定向时携带 Set-Cookie |
| 限制目标 Host | 开启 | 仅跟随相同域名的重定向 |

</details>

<details>
<summary><b>动态变量</b></summary>

Header 模板和 Payload 规则中支持以下动态变量：

| 变量 | 说明 |
|------|------|
| `{{host}}` | 目标主机（含非标准端口） |
| `{{protocol}}` | 协议（http/https） |
| `{{ip}}` | 目标 IP |
| `{{domain}}` / `{{domain.main}}` / `{{subdomain}}` | 完整域名 / 主域名 / 子域名 |
| `{{webroot}}` | 第一级路径段 |
| `{{random.ip}}` / `{{random.local-ip}}` / `{{random.ua}}` | 随机公网 IP / 内网 IP / UA |
| `{{timestamp}}` | Unix 时间戳 |
| `{{date.yyyy}}` / `{{date.MM}}` / `{{date.dd}}` | 日期 |
| `{{time.HH}}` / `{{time.mm}}` / `{{time.ss}}` | 时间 |

</details>

## 指纹规则格式

### 正则规则

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

### Icon Hash 规则

```yaml
Icon_Hash_List:
  - name: Jenkins
    murmur_hash: "81586312"
    md5: ""
    type: Application
    info: Jenkins CI
```

`murmur_hash` 和 `md5` 至少填写一个，Hash 值与 Quake / FOFA 格式兼容。

### 过滤规则

```yaml
Filter_List:
  - name: Response Body 404
    re: '"status":404|"code":404'
    loaded: true
  - name: Replay Attack Detection
    re: '"msg":"检测到重放攻击!"'
    loaded: true
```

当响应体匹配任意已启用过滤规则的正则时，跳过该响应的所有指纹识别（正则匹配 + Icon Hash 匹配均跳过）。适用于某些系统始终返回 HTTP 200 但响应体内嵌真实错误码的场景。

## 编译构建

**环境要求：** JDK 17+ 

**Maven：**

```bash
mvn clean package
# 输出: extender/target/FingerScan-v3.0.1.jar
```

**Gradle（GitHub Actions 使用）：**

```bash
gradle shadowJar
# 输出: build/libs/FingerScan-v3.0.1.jar
```

推送 `v*` 标签会自动触发 GitHub Actions 构建并创建 Release。

## 工作目录

插件加载后，会在 JAR 所在目录下自动创建 `FingerScan/` 文件夹：

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

## 架构

```
BurpExtender (Montoya API)
  ├── ProxyResponseHandler    → 代理流量入口
  └── ContextMenuItemsProvider → 右键菜单入口

ScanOrchestrator
  ├── FilterChain (Method → Host → Suffix)
  └── ScanStrategy[]
      ├── PassiveFingerprintStrategy    被动指纹匹配（支持路径精确匹配）
      ├── IconHashStrategy              Favicon 采集 + Hash
      ├── RecursiveDirectoryScanStrategy 递归目录扫描
      └── PayloadProcessingStrategy     Payload 变换

RequestPipeline
  ├── RequestPool  (50 threads)  HTTP 请求 + 重试
  ├── AnalysisPool (10 threads)  指纹匹配 + Hash 计算
  ├── DeduplicateFilter          URL 去重
  ├── QpsLimiter                 限速
  ├── ResponseFilter             全局响应过滤器（Filter_List）
  └── ResultDispatcher           结果分发

YamlRuleEngine
  ├── 正则规则匹配（re + state + url 路径比对）
  └── 全局过滤拦截（isResponseFiltered → 命中跳过所有规则）
```

## 致谢

本项目基于 [OneScan](https://github.com/vaycore/OneScan) 二次开发，感谢原作者 [vaycore](https://github.com/vaycore) 的贡献。

指纹格式延用[RouteVulScan](https://github.com/F6JO/RouteVulScan)

## License

[GPL-3.0](LICENSE) &mdash; 本项目仅供安全研究和授权测试使用。

