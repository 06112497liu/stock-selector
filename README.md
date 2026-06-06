# ai-stock-selector-java

Java 版「半自动选股建议工具」——量化选股系统 Python 原版的 Java 重写。

> ⚠️ **重要声明,请先读**
>
> - 本系统**只给名单**:对配置好的一篮子标的,输出「**该买 / 继续持有 / 该卖**」三张纯名单。
>   下单要你**自己去券商手动操作**。系统**不自动下单、不记账、不按你的资产规模算股数 / 金额**。
> - 默认因子策略经 **Python 原版的样本外 IC 验证,未发现显著 alpha**。
>   也就是说:**它大概率不能稳定赚钱**。本项目定位是**工程框架 / 学习工具**,
>   用来练习「数据 → 因子 → 选股 → 防前视回测 → 推荐 → Web」这条完整链路。
> - **不构成任何投资建议,别拿去实盘梭哈。** 页面里所有结论仅作演示。

---

## 项目定位

一个把量化选股流程**端到端串通**的练手项目:从抓行情、算因子、横截面标准化、加权选股、
带防前视 + T+1 的回测,到最后给人读的纯名单推荐,再用一个极简 Web 页面展示。

刻意保持「半自动」:系统负责**给判断**,人负责**做决定和执行**。不碰钱、不碰仓位、不碰账本。

---

## 架构分层

数据自下而上流过这几层,每层职责单一、可独立测试:

| 层 | 关键类 | 职责 |
| --- | --- | --- |
| **数据层** | `Bar`、`BarCache`、`YahooClient`/`YahooSource`、`EastMoneyClient`/`EastMoneySource`、`DataSource` | 抓日线行情并落 SQLite 缓存;网络 / 限流失败时**降级返回缓存**,不抛异常。 |
| **因子** | `Factors` | 时间序列因子:动量 `mom_20`、短期反转 `reversal_5`、波动率 `vol_20`。窗口不足处填 NaN。 |
| **横截面标准化** | `CrossSection` | 把某交易日全市场某因子的取值做名次归一化(`rankNormalize`,映射到 [-0.5, 0.5])。 |
| **因子面板** | `MarketPanel` | 按交易日对齐全市场,逐日做横截面标准化。**防前视红线**:某天任一所需因子为 NaN 的票,当天整体剔出横截面,既不参与排名也不可被选。 |
| **选股** | `FactorSelector` | 对每只有效票按权重线性加权各标准化因子打分,取 topN。排序确定(score 降序,code 字典序兜底)。 |
| **回测** | `BacktestEngine`、`BacktestResult`、`Metrics`、`CostConfig` | 等权 topN 日频回测。**信号日 d 选股、d+1 成交**(T+1),成交价取收盘价;计交易成本;输出净值曲线 + 年化 / 夏普 / 最大回撤 / 胜率。 |
| **推荐** | `RecommendEngine`、`Recommendation`、`RecoItem` | 把打分翻译成 buy / hold / sell 三张**纯名单**。卖出只因「打分转负」或「触发个股止损」,**不因掉出 topN 而卖**;数据缺失的持仓保守不动。 |
| **服务装配** | `MarketDataService` | 市场无关:美股 / A 股复用同一个类,只注入不同 `DataSource` 与名称解析函数。`buildPanel()` 组装面板,`names()` 懒加载取名称(失败降级为 code,不编造)。 |
| **Web** | `Application`、`SignalController`、`SignalService`、`web.config.*` | Spring Boot + Thymeleaf。`/signals` 出今日名单,`/backtest` 出样本外回测,`?market=us|cn` 切市场。 |

防前视红线贯穿 **Web → service → MarketPanel → backtest** 全链路:回测页在面板交易日的前 70% 处切「样本外起点」,这个起点只是**信号生成的起始日**;`MarketPanel` 每天的横截面只依赖当天及之前的数据,因此切点不会引入未来信息。

---

## 技术栈

- **Java 17**、**Maven**
- **OkHttp + Jackson**:抓行情 / 名称、解析 JSON
- **SQLite**(`sqlite-jdbc`):日线缓存
- **Spring Boot 3.3 + Thymeleaf**:Web 前端
- **JUnit 5**:单元 / 集成测试,**共 126 个测试,全部通过**

---

## 运行方式

本机命令行没有独立安装 `mvn`,用 IntelliJ IDEA 自带的即可。下文用 `$MVN` 指代:

```bash
MVN="/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn"
```

### 跑测试

```bash
"$MVN" -q test
```

当前应为 `BUILD SUCCESS`,`Tests run: 126, Failures: 0, Errors: 0, Skipped: 0`。

### 启动 Web

```bash
"$MVN" spring-boot:run
```

默认监听 **8080**,主要页面:

- 选股建议:<http://localhost:8080/signals?market=us>(或 `market=cn`)
- 样本外回测:<http://localhost:8080/backtest?market=us>(或 `market=cn`)

`/signals` 还支持可选参数 `holdings`(逗号分隔的持有 code)和 `entryPrice`(`code:price` 逗号分隔的入场价),用来带出 hold / sell 名单与止损判断。

标的篮子和缓存目录在 `src/main/resources/application.yml` 的 `market.*` 配置,不写死在代码里。

### 本机带代理时

如果本机开了代理(如 `127.0.0.1:7897`),curl 请求本地服务记得绕过代理:

```bash
curl --noproxy '*' "http://localhost:8080/signals?market=us"
```

---

## Docker 运行

项目根带了多阶段 `Dockerfile`(Maven 编译 → 精简 JRE 运行)与 `docker-compose.yml`,
一条命令即可构建并起服务。

### 构建 + 启动

```bash
docker compose up -d --build
```

随后访问:

- 选股建议:<http://localhost:8081/signals>(默认美股,`?market=us|cn` 切市场)
- 样本外回测:<http://localhost:8081/backtest?market=us>

> 镜像构建阶段 `-DskipTests` 跳过测试加速(测试已在本机 / CI 跑过)。
> 本机带代理时,curl 本地服务记得 `--noproxy '*'` 绕过代理。

停止:

```bash
docker compose down
```

### 传微信推送 key

`POST /push` 用 Server酱(微信)推送,SendKey 经环境变量 `SERVERCHAN_SENDKEY` 注入,默认空:

```bash
SERVERCHAN_SENDKEY=你的key docker compose up -d --build
```

### 数据卷持久化

容器内缓存目录由环境变量 `MARKET_CACHE_DIR` 指向 `/data`(compose 已配),
SQLite 行情缓存(`us.sqlite` / `cn.sqlite`)落在命名卷 `stock-data` 上,
**容器重建数据不丢**。本机直跑(非容器)时该变量未设,仍沿用默认 `./.cache`,行为不变。

### 容器内联网与代理

- **美股(Yahoo)**:一般可直连,容器内即可拉到行情(本里程碑实测 `/signals?market=us`
  返回 200 且带出真实名单)。
- **若宿主需代理才能访问外网**:容器里的 `127.0.0.1` 是容器自身,要走宿主代理须用
  `host.docker.internal`。在 `docker-compose.yml` 里取消 `JAVA_TOOL_OPTIONS` 注释即可:

  ```yaml
  JAVA_TOOL_OPTIONS: >-
    -Dhttp.proxyHost=host.docker.internal -Dhttp.proxyPort=7897
    -Dhttps.proxyHost=host.docker.internal -Dhttps.proxyPort=7897
    -Dhttp.nonProxyHosts=localhost|127.0.0.1
  ```

- **东财 A 股**:在本机代理环境下常被拦,属**已知限制**;此时靠 SQLite 缓存 / 降级保证
  页面不崩(无数据时友好提示而非 500)。

---

## 已知限制(如实列)

1. **Yahoo 名称端点 429**:行情 `chart` 端点正常可用;但 `v7/finance/quote`(取名称)现在返回 HTTP 429
   (需要 crumb 会话),所以**名称会降级为 code 本身**,行情不受影响。
2. **东财在本机代理下常被拦**:本机代理(`127.0.0.1:7897`)下东财行情 / 名称端点经常被拦,
   **A 股联网取数受限**。此时靠 SQLite 缓存 / 降级保证页面不崩(无数据时友好提示而非 500)。
3. **回测口径从简**:每日调仓;等权组合中「继续持有腿」的微调换手成本计 0;
   `Metrics` 的年化 / 夏普 / 回撤在 **NAV 恒正**的前提下成立。成交价统一取收盘价(面板未暴露开盘价)。
4. **默认因子权重仅 baseline**:`FactorSelector.DEFAULT_WEIGHTS`(`mom_20=1.0, reversal_5=0.5, vol_20=-0.5`)
   只是把流程跑通的基线,**alpha 未经验证**,不应据此实盘。

---

## 测试

```bash
"$MVN" -q test
```

当前 **126 个测试全部通过**(`BUILD SUCCESS`),覆盖数据解析 / 缓存 / 名称降级、因子、横截面、
选股、防前视回测、推荐分流、服务装配与 Web 控制器(含无数据降级)。
