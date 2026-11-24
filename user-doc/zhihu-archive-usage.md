# 知乎存档快照 使用指南

面向最终用户与运维人员的完整说明：如何在自己的浏览器里抓取知乎内容并存入 Halo，以及运维需要了解的接口、配置与限制。

当前部署控制台：<http://192.168.1.114:30890>，存档列表页：<http://192.168.1.114:30890/console/zhihu-archives>

## 目录

1. [功能概览](#1-功能概览)
2. [前置条件](#2-前置条件)
3. [安装用户脚本](#3-安装用户脚本)
4. [使用步骤](#4-使用步骤)
5. [在控制台查看与管理](#5-在控制台查看与管理)
6. [手动调用 API](#6-手动调用-api)
7. [配置项](#7-配置项)
8. [存储与行为说明](#8-存储与行为说明)
9. [限制与校验](#9-限制与校验)
10. [常见问题排查](#10-常见问题排查)

## 1. 功能概览

知乎存档快照把一篇知乎文章、回答、想法或视频，连同正文、作者信息与评论区，保存成 Halo 里的一份私密页面，并保留原始数据与图片资源。

最重要的一点先说清楚：**Halo 里没有 URL 输入框，也没有“粘贴链接就开始抓取”的功能。** 抓取发生在你自己的浏览器里，由一段运行在知乎页面上的 Tampermonkey 用户脚本完成。脚本在你的登录态下读取页面，整理成结构化 payload 后发送给 Halo。Halo 只负责接收这份准备好的数据，落库、下载资源、生成页面。

服务端不抓知乎页面，不保存知乎 Cookie，不设置代理，也没有定时抓取、自动刷新或快照更新，唯一出站请求是下载白名单域名下的图片、头像、公式、海报与视频。原因很直接：知乎内容需登录才完整可见，服务端抓取既拿不到登录态，也容易触发风控。链路是：知乎页面（你的浏览器）→ 用户脚本 → Halo API → 快照页面 + 附件。

## 2. 前置条件

### 2.1 菜单权限

位置：左侧边栏 → 「内容」 分组 → 「知乎存档」。路由 `/zhihu-archives`，完整地址 `/console/zhihu-archives`，排在「评论」和「附件」之间。

|              权限标识              |         说明         |
|--------------------------------|--------------------|
| `system:zhihu-archives:view`   | 只读，可查看列表与详情        |
| `system:zhihu-archives:manage` | 管理，可创建存档、转存视频、重建索引 |

对应角色模板为 `role-template-view-zhihu-archives`（显示名 “Zhihu Archive View”）与 `role-template-manage-zhihu-archives`（显示名 “Zhihu Archive Manage”）。超级管理员不受 RBAC 限制。

如果左侧边栏看不到「知乎存档」，说明账号缺少 `system:zhihu-archives:view`，先让管理员分配角色。

### 2.2 个人令牌（PAT）

用户脚本用个人令牌调用接口。创建路径：个人中心 `/uc` → 个人令牌 → 新建。

关键：**令牌不继承你账号自带的角色**——即使你是超级管理员，只要创建时没有勾选权限，令牌就是“认证通过但零权限”，调用接口一律返回 403。创建弹窗默认停在「角色」标签，那里只列你自己创建的自定义角色（普通账号通常为空），必须手动切到 **「权限」标签** 才能看到角色模板。

还有一个前提：**「权限」标签只列出你这名账号当前已经拥有的权限**。如果列表里根本没有 Zhihu Archives 分组，说明账号本身还没被授予该角色（Halo 也不允许把账号没有的权限授给令牌，创建会报 `Insufficient roles.`）。这时先让管理员在「系统 → 用户」里给账号授权，或者直接用管理员账号（`/uc` 登录管理员）创建令牌：

1. 填名称与过期时间（留空表示永不过期）。
2. 切到「权限」标签，展开模块 **Zhihu Archives**。
3. 勾选 **Zhihu Archive Manage**（`role-template-manage-zhihu-archives`，会自动带上依赖的 Zhihu Archive View）。
4. 生成后令牌只显示一次，立刻复制。

令牌里保存的是**角色名称**而不是权限标识，脚本报错信息里的 `system:zhihu-archives:manage` 是同一件事在权限层面的标识。已创建的令牌无法补加角色（当前版本没有编辑接口），漏勾只能新建一个，再在列表里删除旧令牌。

### 2.3 附件存储策略

资源要写入附件系统，必须指定一个存在的策略。系统预置默认策略名为 `default-policy`；接口会拒绝空的 `policyName`。查看现有策略：控制台 → 附件 → 存储策略。

### 2.4 浏览器与脚本管理器

桌面版 Chrome、Edge 或 Firefox，安装 Tampermonkey。脚本依赖 `GM_getValue`、`GM_setValue`、`GM_xmlhttpRequest` 与 `unsafeWindow`，Tampermonkey 均支持。

## 3. 安装用户脚本

脚本位于仓库的 `/home/halo/userscripts/zhihu-archive/zhihu-archive.user.js`，版本 1.0.4。它**不属于** Halo 控制台的前端打包产物，需要手动装到自己的电脑上。

1. 打开 Tampermonkey 面板，点击「新建脚本」。
2. 清空编辑器里的默认模板。
3. 把 `zhihu-archive.user.js` 的完整内容粘贴进去。
4. 按 Ctrl+S 保存，确认脚本处于启用状态。

脚本只在以下页面生效（脚本头部的 `@match` 白名单）：

- `https://zhuanlan.zhihu.com/p/*`
- `https://www.zhihu.com/question/*/answer/*`
- `https://www.zhihu.com/pin/*`
- `https://www.zhihu.com/zvideo/*`

## 4. 使用步骤

1. 在浏览器登录知乎，打开要存档的内容，地址需落在上面四种范围内。
2. 页面右上角出现固定面板，标题 “Halo Zhihu Archive 1.0.4”，并显示识别结果，如 `Detected: ARTICLE (<id>)`，类型可能是 `ARTICLE`、`ANSWER`、`PIN`、`VIDEO`；不支持的页面显示 `Detected: unsupported page`。
3. 首次使用点「Settings」，填写四个字段：
   - Halo base URL：`http://192.168.1.114:30890`，**结尾不要带斜杠**。
   - Personal Access Token：粘贴个人令牌。这是密码输入框；已保存过则留空表示沿用。
   - Attachment policy name：存储策略名，如 `default-policy`。
   - Snapshot visibility：`PUBLIC`（默认，进入公开索引页）或 `PRIVATE`（仅作者与管理员可见）。
4. 点「Save」，出现 “Saved locally in userscript storage.” 表示已写入脚本本地存储。
5. 点「Capture & send」。脚本读取页面、整理 payload、逐个上传，进度显示 `Step: <step>`。资源越多越慢。
6. 成功后显示 “Snapshot created.” 并给出快照链接，点开即可查看。
7. 内容含视频时，之后可在控制台列表里「转存视频」，见下一节。

## 5. 在控制台查看与管理

打开 <http://192.168.1.114:30890/console/zhihu-archives>，或从左侧边栏进入。

列表列依次为：标题 / 作者 / 来源类型 / 抓取时间 / 可见性 / 操作。页面顶部有隐私提示：**存档包含作者与评论的个人信息；默认公开，请确认后再发布。** 列表为空时显示：暂无知乎存档，抓取知乎内容会显示在这里。

- **转存视频**：只在该行 `video-state` 注解为 `none` 且存在 `video-source-url` 注解时出现。它需要此前保存的策略名，下载时带 `Referer: https://www.zhihu.com/`。完成后生成新的 `<page>-video-<hash8>` 快照，把正文里的视频 figure 改写成指向本地的 `<video>`，并将 `video-state` 置为 `transferred`。已转存的再点不会重复执行。
- **重建索引**：对应 `POST /zhihu-archives/index/rebuild`，刷新公开索引页。

公开索引页是一张名为 `zhihu-archive-index` 的 PUBLIC 页面，slug 为 `zhihu-archives`，标题 “Zhihu Archives”，公开地址 <http://192.168.1.114:30890/zhihu-archives>。每次创建成功后都会尽力重建一次，失败不影响存档。索引为空时显示 “暂无公开存档。”。

## 6. 手动调用 API

接口基地址：

```bash
http://192.168.1.114:30890/apis/api.console.halo.run/v1alpha1
```

创建存档（`CreateZhihuArchive`），请求体为 `{payload, policyName, visible}`，认证使用 Bearer 令牌。`visible` 可省略，省略时为 `PUBLIC`；只接受 `PUBLIC` 或 `PRIVATE`：

```bash
curl -X POST 'http://192.168.1.114:30890/apis/api.console.halo.run/v1alpha1/zhihu-archives' \
  -H 'Authorization: Bearer <PAT>' \
  -H 'Content-Type: application/json' \
  -d '{"policyName":"default-policy","visible":"PUBLIC","payload":{
    "schemaVersion": 1,
    "source": {"url":"https://zhuanlan.zhihu.com/p/123456789","type":"ARTICLE","id":"123456789"},
    "article": {
      "title": "示例标题",
      "author": {"name":"示例作者","profileUrl":"https://www.zhihu.com/people/example"},
      "publishedAt": "2026-01-01T00:00:00Z",
      "likeCount": 10, "commentCount": 2,
      "html": "<p>正文</p>"
    },
    "comments": {"total": 2, "roots": [{
      "id": "c1", "author": {"name":"读者","profileUrl":"https://www.zhihu.com/people/r1"},
      "contentHtml": "<p>评论</p>", "likeCount": 3,
      "createdAt": "2026-01-01T01:00:00Z", "ipLocation": "上海",
      "badge": "", "collapsed": false, "replyToAuthor": "", "replies": []
    }]},
    "capture": {"capturedAt":"2026-01-01T02:00:00Z","clientVersion":"1.0.4"},
    "assets": {"items": [{"url":"https://pic1.zhimg.com/example.jpg","type":"IMAGE"}]}
  }}'
```

其他接口：

|  方法  |                   路径                    |                                                 说明                                                  |
|------|-----------------------------------------|-----------------------------------------------------------------------------------------------------|
| GET  | `/zhihu-archives?page=1&size=20`        | 列表（`ListZhihuArchives`）。`page` 默认 1，`size` 默认 20，最大 100，返回 `{page,size,total,items}`，元素为 SinglePage |
| POST | `/zhihu-archives/{name}/transfer-video` | 转存视频，`{name}` 为 `metadata.name`                                                                     |
| POST | `/zhihu-archives/index/rebuild`         | 重建公开索引页                                                                                             |

payload 字段说明：

|                      字段                      | 必填 |                                            说明                                            |
|----------------------------------------------|----|------------------------------------------------------------------------------------------|
| `schemaVersion`                              | 是  | 必须正好是 `1`                                                                                |
| `source.url`                                 | 是  | 来源地址，校验规则见第 9 节                                                                          |
| `source.type`                                | 是  | `ARTICLE`、`ANSWER`、`PIN`、`VIDEO` 之一                                                      |
| `source.id`                                  | 是  | 知乎侧内容 ID，最长 128，参与幂等键计算                                                                  |
| `article.title`                              | 是  | 标题，最长 500                                                                                |
| `article.author`                             | 是  | `{name, profileUrl}`；`name` 必填、最长 200，`profileUrl` 可选                                    |
| `article.publishedAt`                        | 否  | ISO-8601 时间                                                                              |
| `article.likeCount` / `article.commentCount` | 否  | 点赞数 / 评论数，不能为负                                                                           |
| `article.html`                               | 是  | 正文 HTML，最长 2,000,000 字符                                                                  |
| `comments`                                   | 是  | 评论对象，`total` 可选，`roots` 必填                                                               |
| `comments.roots[].id`                        | 否  | 评论 ID，最长 128                                                                             |
| `comments.roots[].author`                    | 是  | `{name, profileUrl}`，规则同文章作者                                                             |
| `comments.roots[].contentHtml`               | 是  | 评论 HTML，最长 20,000 字符                                                                     |
| `comments.roots[].likeCount`                 | 否  | 点赞数，决定渲染排序                                                                               |
| `comments.roots[]` 其余字段                      | 否  | `createdAt`、`ipLocation`、`badge`、`collapsed`、`replyToAuthor`、`replies`（结构相同）             |
| `capture`                                    | 是  | `{capturedAt, clientVersion}`；`capturedAt` 必填，`clientVersion` 最长 64，脚本上报 `1.0.4`         |
| `assets` / `assets.items[]`                  | 否  | 可整体省略；每项为 `{url, type}`，最多 1000 条；type 为 `IMAGE`、`AVATAR`、`EQUATION`、`POSTER`、`VIDEO` 之一 |
| `policyName`                                 | 是  | 附件存储策略名，非空且不超过 253 字符                                                                    |
| `visible`                                    | 否  | 快照可见性，`PUBLIC` 或 `PRIVATE`，默认 `PUBLIC`；同一来源重新提交时会更新已有快照的可见性                              |

## 7. 配置项

四个 `halo.zhihu-archive.*` 配置键都有默认值，**全部可选**，不写也能正常运行。改完配置需重启应用生效。

|                  配置键                  |           默认值            |                 说明                 |
|---------------------------------------|--------------------------|------------------------------------|
| `halo.zhihu-archive.media-hosts`      | `[zhimg.com, zhihu.com]` | 允许下载的媒体域名，匹配域名本身及所有子域名，仅 HTTPS/443 |
| `halo.zhihu-archive.max-asset-size`   | `10MB`                   | 单个图片类资源上限                          |
| `halo.zhihu-archive.max-video-size`   | `200MB`                  | 单个视频资源上限                           |
| `halo.zhihu-archive.response-timeout` | `15s`                    | 资源下载的响应超时                          |

```yaml
halo:
  zhihu-archive:
    media-hosts:
      - zhimg.com
      - zhihu.com
    max-asset-size: 10MB
    max-video-size: 200MB
    response-timeout: 15s
```

对应环境变量（`media-hosts` 用逗号分隔）：

```bash
HALO_ZHIHU_ARCHIVE_MEDIA_HOSTS=zhimg.com,zhihu.com
HALO_ZHIHU_ARCHIVE_MAX_ASSET_SIZE=10MB
HALO_ZHIHU_ARCHIVE_MAX_VIDEO_SIZE=200MB
HALO_ZHIHU_ARCHIVE_RESPONSE_TIMEOUT=15s
```

## 8. 存储与行为说明

这个功能**没有新增自定义资源类型（GVK）**，用的都是 Halo 已有资源。一次成功创建会产生：

- 一张 `SinglePage`（`content.halo.run/v1alpha1`）。名称为 `zhihu-` 加 `sha256("<TYPE>:<id>")` 的前 12 位十六进制；`spec.slug` 为 `zhihu-<清洗后的来源 id>`。页面 `visible` 默认为 `PUBLIC`（请求 `visible: "PRIVATE"` 时为 `PRIVATE`），`publish` 为 `true`，`allowComment` 为 `false`，robots meta 为 `noindex`。`status.permalink` 形如 `/<slug>`。
- 一个 `Snapshot`，名称为 `<pageName>-v1`，`rawType` 为 `HTML`，保存渲染后的页面。
- 一个 `Attachment`，名称为 `zhihu-payload-<archiveKey>.json`，内容为原始 JSON，归入附件分组 `zhihu-archive`。
- 若干 `Attachment`，对应正文引用的资源，写入所选存储策略。

幂等性按来源类型加来源 ID 计算。**重复抓取同一内容会直接返回已有快照，不会更新它。** 系统没有更新或重新抓取的接口。内容哈希只记录在注解 `zhihu-archive.halo.run/content-hash`，不参与幂等判断。

可见性：快照默认为公开，匿名访客也能打开；选择 PRIVATE 时只有作者本人或管理员能访问。公开索引页 `/zhihu-archives` 是公开的，且只列出 PUBLIC 快照——PRIVATE 快照不会出现在索引里，只能从控制台列表进入。

## 9. 限制与校验

来源 URL 校验：

- 只接受 HTTPS。
- 主机名必须正好是 `zhihu.com`、`www.zhihu.com` 或 `zhuanlan.zhihu.com`，不接受其它子域名。
- 不允许出现 userinfo（`user:pass@` 形式）。
- 端口必须省略或为 443。

各项上限：

|         项目          |                        上限                         |
|---------------------|---------------------------------------------------|
| 正文 `article.html`   | 2,000,000 字符                                      |
| 单条评论 `contentHtml`  | 20,000 字符                                         |
| 标题                  | 500 字符                                            |
| 作者名                 | 200 字符                                            |
| URL                 | 2048 字符                                           |
| 资源条数 `assets.items` | 1000                                              |
| 顶层评论                | 最多提交 200 条，按点赞数只渲染前 10 条                          |
| 每条顶层评论的回复           | 最多提交 500 条，只渲染前 50 条                              |
| `policyName`        | 非空，不超过 253 字符                                     |
| `visible`           | `PUBLIC` 或 `PRIVATE`，省略为 `PUBLIC`                 |
| 整个请求体               | 10MB，由 `spring.http.codecs.max-in-memory-size` 限制 |

评论的“提交”和“渲染”是两回事：超出渲染数量的评论仍会被接收，只是不进页面。

## 10. 常见问题排查

**返回 401。** 令牌缺失、写错或失效，也可能令牌对应的角色不含管理权限。重新在 `/uc` 创建令牌，角色选 “Zhihu Archive Manage”，再在脚本 Settings 里更新。

**接口 404。** 多半是 Halo base URL 填错，例如多了路径、结尾斜杠或指向别的端口；也可能是当前部署还没有这个功能。确认 base URL 是 `http://192.168.1.114:30890`，不带结尾斜杠。若面板报错里带 `Group/zhihu-archive was not found`，说明服务端 jar 缺少附件分组种子（`extensions/zhihu-archive-group.yaml`），重新部署并重启 Halo 即可，无需改脚本。

**设置保存时报 “Halo base URL must start with http:// or https://.”。** 地址缺少协议前缀，补上 `http://` 或 `https://` 后重试。

**提示需要令牌（token required）。** 令牌输入框留空，同时脚本本地也没有已保存令牌。填入令牌再保存。

**部分图片或视频没下载下来。** 失败资源会被跳过并记录在注解 `zhihu-archive.halo.run/asset-failures`，快照本身照常创建。常见原因是域名不在 `media-hosts` 白名单、超过 `max-asset-size` 或 `max-video-size`、或源站拒绝请求。核对第 7 节配置，必要时把对应域名加入白名单。

**快照创建了但别人看不到。** 先确认抓取时选的可见性：PRIVATE 只有作者或管理员能访问，这是预期行为；PUBLIC 快照可用匿名窗口验证。此外快照是独立页面，不会出现在首页文章流，公开入口是索引页 `/zhihu-archives`（可在「外观 → 菜单」里把它加进导航）。

**已有快照想从私密改成公开。** 用同一内容重新抓取并在 Settings 里选 PUBLIC，服务端会把已有快照的可见性改掉并重建索引；也可以在控制台编辑该页面的可见性，然后回列表点「重建索引」。

**左侧边栏没有「知乎存档」。** 账号缺少 `system:zhihu-archives:view`。让管理员分配 “Zhihu Archive View” 或 “Zhihu Archive Manage” 角色。

**抓取时面板一直停在某个 Step。** 通常是网络慢或资源多。先确认 Halo 地址可达，再检查浏览器开发者工具里的请求；请求体超过 10MB 会被拒绝，这种情况需要减少抓取范围。

**列表里没有「转存视频」按钮。** 只有 `video-state` 为 `none` 且存在 `video-source-url` 注解的行才显示。已转存（`transferred`）或非视频来源不会显示。
