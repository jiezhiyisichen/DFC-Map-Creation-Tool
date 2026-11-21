# 代码上传 GitHub 与 Gitee 完整指南

> 适用环境：Windows 10/11 · Git Bash（`bash` 命令行）· 任意语言的项目（示例以 Java/Gradle 为主）
> 本文覆盖三件事：**上传前准备** → **具体上传步骤** → **访问令牌（Token）的配置与使用**。
> 所有命令都可以直接复制执行，`<尖括号>` 里的内容按你自己的情况替换。

---

## 目录

1. [两个平台先搞清楚差异](#1-两个平台先搞清楚差异)
2. [上传前准备](#2-上传前准备)
3. [GitHub：从零到推送成功](#3-github从零到推送成功)
4. [Gitee：从零到推送成功](#4-gitee从零到推送成功)
5. [一套代码同时推两个平台](#5-一套代码同时推两个平台)
6. [Token（访问令牌）专题](#6-token访问令牌专题)
7. [日常提交与发布](#7-日常提交与发布)
8. [故障排查速查表](#8-故障排查速查表)
9. [命令速查卡](#9-命令速查卡)

---

## 1. 两个平台先搞清楚差异

| 项目 | GitHub | Gitee（码云） |
|---|---|---|
| 默认分支名 | `main` | `master`（可改成 `main`） |
| 密码推送 | **不支持**（2021-08 起），必须用 Token 或 SSH | **不支持**，必须用私人令牌或 SSH |
| 网页建仓 | 可建空仓，也可勾选 README 初始化 | 可建空仓，也可勾选初始化 |
| 单文件上限 | 100 MB 硬限制（>50 MB 警告），更大需 Git LFS | 100 MB 左右，具体以官网当前说明为准 |
| 仓库容量 | 建议 <1 GB，超过 5 GB 会警告 | 免费账号有容量上限，超限需清理或升级 |
| 镜像 | 无官方镜像（用 Actions / 第三方） | **自带仓库镜像管理**，可自动从 GitHub 同步 |
| 访问速度（国内） | 时快时慢，可能需要代理 | 通常更快 |

**结论**：两个都推是常见做法 —— GitHub 面向前台展示与 Release，Gitee 保证国内访问与备份。

---

## 2. 上传前准备

### 2.1 确认 Git 可用

```bash
git --version
```

没有的话去 <https://git-scm.com/download/win> 安装（安装时选 "Git Bash Here" 与 "Use Git from Windows Command Prompt" 都可以）。

确认当前目录是不是已经是一个仓库：

```bash
cd /d/你的项目目录
git rev-parse --is-inside-work-tree   # 输出 true = 已经是仓库
```

### 2.2 配置身份（只做一次，全局生效）

```bash
git config --global user.name  "你的名字或昵称"
git config --global user.email "你的邮箱@example.com"
```

> 邮箱建议与 GitHub/Gitee 账号主邮箱一致，提交记录才会正确归属到你名下。
> 只想对当前项目生效，去掉 `--global` 即可（配置写进 `.git/config`，不会跟仓库一起上传）。

查看当前配置：

```bash
git config --global --list
```

### 2.3 中文与换行（Windows 必做）

```bash
# 中文路径不要被转义成 \344\275\240 这种
git config --global core.quotepath false
# 提交信息按 UTF-8 存，避免中文乱码
git config --global i18n.commitEncoding utf-8
git config --global i18n.logOutputEncoding utf-8
# 换行：检出时保持 Windows 风格，提交时统一成 LF
git config --global core.autocrlf true
```

### 2.4 写 `.gitignore`（最重要的一步）

仓库里**不该出现**编译产物、IDE 配置、日志、本地缓存。Java/Gradle 项目可直接用：

```gitignore
# 构建产物
build/
out/
bin/
target/
*.class

# Gradle
.gradle/
!gradle/wrapper/gradle-wrapper.jar
!gradle/wrapper/gradle-wrapper.properties

# IDE
.idea/
*.iml
*.ipr
*.iws
.classpath
.project
.settings/
.vscode/

# 运行/日志
run/
logs/
*.log
*.log.gz

# 系统
Thumbs.db
.DS_Store

# 本地密钥与令牌（务必写进来）
*.token
*token*.txt
secrets.*
.env
.env.*
```

> ⚠️ **注意 `gradle-wrapper.jar`**：它是 Gralde Wrapper 的一部分，**必须提交**，
> 所以要用 `!gradle/wrapper/gradle-wrapper.jar` 把它从 `*.jar` 规则里救回来。
> 本项目根目录有 `bin/`（Eclipse 输出）、`run/`、`logs/`，都已在上面的规则里。

### 2.5 敏感信息自查（上传前必做）

```bash
# 1) 看看到底有哪些文件会被提交
git status --porcelain

# 2) 扫一遍代码里有没有硬编码的密钥
git grep -n -i -E "token|secret|password|api[_-]?key|access[_-]?key" -- . ":(exclude)*.md"

# 3) 扫已提交的历史（仓库大的话会慢）
git log -p --all | grep -i -n -E "ghp_|gho_|glpat-|access_token" | head -20
```

出现命中不要慌，先判断是不是占位符（`<your-token>`、`xxxx`）。
**真令牌绝不要提交**：一旦进了历史，即使后面删掉，历史里还在。

如果已经误提交，正确处理见 [6.4 令牌泄露了怎么办](#64-令牌泄露了怎么办)。

### 2.6 大文件自查

```bash
# 找出大于 20MB 的文件
find . -size +20M -not -path "./.git/*" -not -path "./build/*" -print
```

超过 100 MB 的单文件直接推不上去。三种处理方式：

1. 不要提交它（加进 `.gitignore`，改用网盘/Release 附件分发）；
2. 用 **Git LFS** 管理二进制大文件；
3. 先提交再清理历史（麻烦，尽量避免）。

### 2.7 初始化本地仓库（两种情形）

**情形 A：全新项目，还没有 `.git`**

```bash
cd /d/你的项目目录
git init
git branch -M main            # 统一用 main（想用 master 就跳过这行）
git add -A
git commit -m "initial commit"
```

**情形 B：已经有 `.git`（本项目就属于这种）**

```bash
git status                    # 确认干净，或先把该提交的提交掉
git log --oneline -3          # 看一眼历史
git branch --show-current     # 当前分支
```

> 本项目当前已配置两个远端：`origin` → GitHub、`gitee` → Gitee，分支为 `main`，
> 可直接用 `git remote -v` 核对，然后跳到「第 5 节」做双平台推送。

---

## 3. GitHub：从零到推送成功

### 3.1 网页建仓库

1. 登录 <https://github.com>，右上角 **+** → **New repository**。
2. 填 **Repository name**（建议英文、短横线分隔，例如 `DFC-Map-Creation-Tool`）。
3. **Description** 选填；**Public / Private** 按需选。
4. **不要勾选** "Add a README file / .gitignore / license" —— 本地已有内容时勾了会产生两条不相关的历史，
   后面第一次 push 会被拒（需要 `--allow-unrelated-histories` 合并）。
5. 点 **Create repository**，页面会给出仓库地址：
   - HTTPS：`https://github.com/<用户名>/<仓库名>.git`
   - SSH：`git@github.com:<用户名>/<仓库名>.git`

### 3.2 认证方式一：HTTPS + 个人访问令牌（PAT）

GitHub 已经不接受账号密码。要推送，必须生成一个 Token 当密码用。

#### 生成 Classic Token（最省事）

1. 右上角头像 → **Settings**
2. 左侧最下 → **Developer settings**
3. **Personal access tokens** → **Tokens (classic)**
4. **Generate new token** → **Generate new token (classic)**
5. 填写：
   - **Note**：给它起个能认出来的名字，例如 `win-pc-push`
   - **Expiration**：建议 30 / 90 天（永久令牌一旦泄露风险最大）
   - **Scopes**：至少勾 **`repo`**（读写仓库）；要改 GitHub Actions 再加 **`workflow`**；
     要发包到 GitHub Packages 再加 `write:packages`
6. 点 **Generate token** —— **令牌只显示这一次**，立刻复制保存到密码管理器。

#### 生成 Fine-grained Token（权限更细，推荐长期用）

1. 同上的 **Developer settings** → **Personal access tokens** → **Fine-grained tokens**
2. **Generate new token**
3. **Repository access**：`Only select repositories` → 选中目标仓库（最小权限）
4. **Permissions** → **Repository permissions**：
   - `Contents` → **Read and write**（推送代码必需）
   - `Metadata` → 自动为 Read-only
   - 需要操作 Actions 时：`Workflows` → Read and write
5. 生成后同样只显示一次，立刻保存。

#### 用令牌推送

第一次推送时，Git 会提示输入用户名与密码：

```bash
git remote add origin https://github.com/<用户名>/<仓库名>.git
git push -u origin main
# Username: 你的 GitHub 用户名
# Password: 粘贴 Token（不是账号密码，输入时不显示字符，属正常）
```

也可以把令牌直接写进一次性命令（**不推荐**，会留在 shell 历史里）：

```bash
git push https://<用户名>:<TOKEN>@github.com/<用户名>/<仓库名>.git main
```

#### 让 Git 记住令牌（推荐）

Git for Windows 自带 **Git Credential Manager (GCM)**，令牌会加密存进 Windows 凭据管理器：

```bash
git config --global credential.helper manager
```

之后第一次输入过就不再问。想改/删：控制面板 → **凭据管理器** → **Windows 凭据** →
找到 `git:https://github.com` 删除即可（下次推送会重新询问）。

> Linux 用 `credential.helper libsecret`（或 `cache --timeout=3600`），macOS 用 `osxkeychain`。
> **不要用 `credential.helper store`** —— 它把令牌明文写进 `~/.git-credentials`。

### 3.3 认证方式二：SSH 密钥（长期最省事，推荐）

```bash
# 1) 生成密钥（一路回车；想加密码就输入 passphrase）
ssh-keygen -t ed25519 -C "你的邮箱@example.com"

# 2) 查看公钥，整段复制
cat ~/.ssh/id_ed25519.pub
```

3. GitHub → **Settings** → **SSH and GPG keys** → **New SSH key** → 粘贴公钥 → 保存。

```bash
# 4) 测试连接（首次会问 yes/no，输入 yes）
ssh -T git@github.com
# 成功提示：Hi <用户名>! You've successfully authenticated...
```

如果 22 端口被网络阻断，走 443：

```bash
ssh -T -p 443 git@ssh.github.com
```

或在 `~/.ssh/config` 里固定：

```
Host github.com
  HostName ssh.github.com
  Port 443
  User git
```

用 SSH 地址作为远端：

```bash
git remote set-url origin git@github.com:<用户名>/<仓库名>.git
```

### 3.4 推送

```bash
git push -u origin main
```

- `-u` 表示把 `origin/main` 设为当前分支的上游，之后直接 `git push` 即可。
- 推送成功后会打印 `branch 'main' -> 'main'` 与一个对比链接。

---

## 4. Gitee：从零到推送成功

### 4.1 网页建仓库

1. 登录 <https://gitee.com>，右上角 **+** → **新建仓库**。
2. 填 **仓库名称**、**路径**（路径就是 URL 里的那段）、**是否开源**。
3. **不要勾选** "使用 Readme 文件初始化这个仓库"，理由同 GitHub。
4. 创建后得到地址：
   - HTTPS：`https://gitee.com/<用户名>/<仓库路径>.git`
   - SSH：`git@gitee.com:<用户名>/<仓库路径>.git`

> Gitee 会把 `master` 当默认分支。想让两边一致，建仓后在
> **管理 → 仓库设置 → 默认分支** 改成 `main`，或本地就用 `main` 推上去再改默认。

### 4.2 生成私人令牌（Gitee 的 Token）

1. 右上角头像 → **设置**
2. 左侧 **安全设置** → **私人令牌**
3. **生成新令牌**
4. 填写描述（例如 `win-pc-push`），勾选权限：
   - **`projects`** —— 仓库读写（推送必需）
   - 需要调 API 管理 Issue / PR 再按需勾选对应项
5. 提交后**令牌只显示一次**，立刻复制保存。

### 4.3 用令牌推送（HTTPS）

```bash
git remote add gitee https://gitee.com/<用户名>/<仓库路径>.git
git push -u gitee main
# Username: Gitee 用户名（或绑定的手机号/邮箱）
# Password: 私人令牌
```

同样可以用凭据管理器记住（与 GitHub 共用 `credential.helper manager`）。

### 4.4 用 SSH 推送

```bash
# 已有 ed25519 密钥的话跳过生成
ssh-keygen -t ed25519 -C "你的邮箱@example.com"
cat ~/.ssh/id_ed25519.pub
```

Gitee → **设置** → **SSH 公钥** → 粘贴 → 确定。

```bash
ssh -T git@gitee.com
# 成功提示：Hi <用户名>! You've successfully authenticated...
```

```bash
git remote set-url gitee git@gitee.com:<用户名>/<仓库路径>.git
git push -u gitee main
```

### 4.5 Gitee 独有：仓库镜像（自动同步）

适合"以 GitHub 为主、Gitee 只做国内镜像"的场景，不用每次手动推：

1. Gitee 仓库页 → **管理** → **仓库镜像管理**
2. **添加镜像** → 选择 **Pull（拉取）** 或 **Push（推送）**
3. 填 GitHub 仓库地址；私有仓库需要 GitHub 的 Token 作凭据
4. 可设置定时同步（例如每天一次）

> 镜像只同步分支与标签，**不会**同步 Issues、Wiki、Release 附件。

---

## 5. 一套代码同时推两个平台

推荐做法：一个 `origin`（主）+ 一个 `gitee`（备），分别推。

```bash
# 一次性配置
git remote add origin https://github.com/<用户名>/<仓库名>.git
git remote add gitee  https://gitee.com/<用户名>/<仓库路径>.git

# 首次推送
git push -u origin main
git push -u gitee  main

# 以后每次
git push origin main
git push gitee  main
```

嫌麻烦可以给 `origin` 挂第二个 push 地址，一条命令推两个：

```bash
git remote set-url --add --push origin https://github.com/<用户名>/<仓库名>.git
git remote set-url --add --push origin https://gitee.com/<用户名>/<仓库路径>.git
git push origin main          # 会依次推到两个地址
```

查看/撤销：

```bash
git remote -v                  # 会看到 origin 有两个 (push) 行
git remote set-url --delete --push origin https://gitee.com/...
```

> 也可以写成脚本 `push-all.sh`（放本地、加进 `.gitignore`）：
>
> ```bash
> #!/usr/bin/env bash
> set -e
> git push origin main
> git push gitee  main
> echo "both pushed"
> ```

---

## 6. Token（访问令牌）专题

### 6.1 令牌到底是什么

Token 是一串**代替密码**的字符串，能限定权限范围和有效期。它等价于"只读/只写某个仓库"的钥匙，
所以**泄露的后果比密码小、但仍然危险**：拿到 `repo` 权限的令牌等于能改你的代码。

### 6.2 四种存放方式（按推荐度排序）

| 方式 | 命令/位置 | 安全性 | 说明 |
|---|---|---|---|
| **凭据管理器**（推荐） | `git config --global credential.helper manager` | 高 | 加密存进系统凭据库，Git 自动取用 |
| **SSH 密钥**（推荐） | `~/.ssh/id_ed25519` | 高 | 根本不涉及令牌 |
| 环境变量 | `export GH_TOKEN=...` | 中 | 只对当前终端会话有效，适合临时脚本/CI |
| 明文文件 | `credential.helper store` → `~/.git-credentials` | **低** | 明文保存，仅限隔离环境 |

写入 remote URL（`https://user:token@host/...`）属于**反面做法**：令牌会同时出现在
`.git/config`（虽然不随仓库上传，但会被备份/截图带出去）和 shell 历史里。

### 6.3 在脚本 / CI 里用令牌

```bash
# 本地临时用（不落盘）
read -s GIT_TOKEN            # 输入不回显
git push "https://<用户名>:$GIT_TOKEN@github.com/<用户名>/<仓库名>.git" main
unset GIT_TOKEN
```

GitHub Actions 里用 Secrets：

```yaml
- uses: actions/checkout@v4
  with:
    token: ${{ secrets.GITHUB_TOKEN }}   # 内置，无需自建
```

要跨仓库操作才自建 PAT，并在仓库 **Settings → Secrets and variables → Actions** 里存成
`secrets.MY_PAT`，**绝不**写进 workflow 文件。

### 6.4 令牌泄露了怎么办

1. **立刻吊销**：
   - GitHub：Settings → Developer settings → 对应 Token → **Delete / Revoke**
   - Gitee：设置 → 安全设置 → 私人令牌 → 删除
2. 生成新令牌，替换本地凭据（Windows 凭据管理器里删掉旧条目）。
3. 如果令牌被提交进了仓库：仅删除文件**不够**，历史里仍在。
   ```bash
   # 需要 git-filter-repo（pip install git-filter-repo）
   git filter-repo --path path/to/leaked.txt --invert-paths
   git push --force --all
   git push --force --tags
   ```
   并且通知协作者重新 clone（历史已重写）。
4. 检查该令牌期间的异常操作记录（GitHub 的 Settings → Security log）。

### 6.5 有效期与轮换

- 给令牌设 **30~90 天**有效期，到期前重新生成并替换。
- 不同用途用**不同令牌**（家用 / 公司 / CI），泄露时只吊销一个。
- 命名带上用途与机器，例如 `home-pc-push`、`ci-release`，方便识别。

---

## 7. 日常提交与发布

### 7.1 标准三步

```bash
git status                  # 看改了什么
git add -A                  # 全部暂存（或 git add 具体文件）
git commit -m "说明这次改了什么"
git push                    # 有上游后直接推
```

### 7.2 提交信息别被引号截断

Windows 下引号/长度容易把命令截断，稳妥做法是写进文件：

```bash
printf '修复：xxx\n\n- 细节 1\n- 细节 2\n' > /tmp/msg.txt
git commit -F /tmp/msg.txt
```

### 7.3 分支

```bash
git switch -c feature/xxx    # 新建并切换
git push -u origin feature/xxx
git switch main
git merge feature/xxx
```

### 7.4 标签与 Release

```bash
git tag -a v1.0.0 -m "v1.0.0"
git push origin v1.0.0
git push gitee  v1.0.0
```

然后在网页端 **Releases → Draft a new release** 选择该标签、写说明、上传编译产物
（`.jar` / `.zip` 这类二进制**不要提交进仓库**，放 Release 附件）。

---

## 8. 故障排查速查表

| 现象 | 原因 | 处理 |
|---|---|---|
| `Authentication failed` / `Support for password authentication was removed` | 用了账号密码 | 改用 Token 或 SSH（第 3.2 / 3.3 节） |
| `remote: Invalid username or password` | 令牌过期或权限不足 | 重新生成，确认勾了 `repo` / `projects` |
| `Permission denied (publickey)` | SSH 公钥没加到平台 | 重新粘贴 `~/.ssh/id_ed25519.pub`，`ssh -T` 验证 |
| `error: failed to push some refs ... non-fast-forward` | 远端有你本地没有的提交 | `git pull --rebase origin main` 后再推 |
| `refusing to merge unrelated histories` | 建仓时勾了 README 初始化 | `git pull origin main --allow-unrelated-histories` |
| `fatal: remote origin already exists` | 远端名重复 | `git remote set-url origin <新地址>` |
| `502` / `schannel closed` / 连接超时 | 本地代理与应用层 TLS 冲突 | 见下方"代理问题" |
| `filename too long` | Windows 路径长度限制 | `git config --global core.longpaths true` |
| 中文文件名显示成 `\344\275\240` | 未关 quotepath | `git config --global core.quotepath false` |
| 中文提交信息乱码 | 编码不一致 | 见 2.3 的三条 `i18n.*` / `core.autocrlf` |
| 推送很慢或卡住 | 体积过大 | 检查 `build/`、`logs/` 是否被误提交；用 LFS |

**代理问题**（国内网络常见）：先确认是否设了代理：

```bash
git config --global --get http.proxy
env | grep -i proxy
```

临时绕过代理推送（Git Bash）：

```bash
git -c http.proxy= -c https.proxy= push origin main
# 或清掉环境变量再执行
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY git push origin main
```

只想给 GitHub 走代理、Gitee 直连，可以按域名设：

```bash
git config --global http.https://github.com.proxy http://127.0.0.1:7890
git config --global --unset http.https://github.com.proxy   # 取消
```

---

## 9. 命令速查卡

```bash
# —— 准备 ——
git --version
git config --global user.name  "名字"
git config --global user.email "邮箱"
git config --global core.quotepath false
git config --global credential.helper manager

# —— 初始化与首次推送 ——
cd /d/项目目录
git init
git branch -M main
git add -A
git commit -m "initial commit"
git remote add origin https://github.com/<用户名>/<仓库名>.git
git push -u origin main

# —— 加第二个平台 ——
git remote add gitee https://gitee.com/<用户名>/<路径>.git
git push -u gitee main

# —— 每次提交 ——
git status
git add -A
git commit -m "说明"
git push origin main
git push gitee  main

# —— 核对与排查 ——
git remote -v
git log --oneline -5
git config --global --list
ssh -T git@github.com
ssh -T git@gitee.com
```

---

## 附：检查清单（照着打勾就不会漏）

**上传前**
- [ ] `git config user.name / user.email` 已设
- [ ] `.gitignore` 已覆盖 `build/`、`.gradle/`、`run/`、`logs/`、IDE 文件
- [ ] `!gradle/wrapper/gradle-wrapper.jar` 例外规则已加
- [ ] 敏感信息自查无命中（令牌 / 密码 / 密钥）
- [ ] 无 >100 MB 单文件
- [ ] 平台建仓时**没有**勾选 README 初始化

**认证**
- [ ] GitHub：Token 勾了 `repo`（或已配 SSH）
- [ ] Gitee：私人令牌勾了 `projects`（或已配 SSH）
- [ ] 令牌已存进凭据管理器 / 密码管理器，**没有**写进代码或 remote URL

**推送后**
- [ ] 网页上能看到全部文件，`build/` 之类的没被带上去
- [ ] 提交记录里的作者名/邮箱正确
- [ ] 默认分支与本地一致（`main`）
- [ ] Release 附件已上传（如果这次是发版）
