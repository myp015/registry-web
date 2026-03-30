# docker-release.sh 用法

通用发布脚本：`scripts/docker-release.sh`

目标：
- 支持任意项目（只要能通过 `--build-cmd` 先产出构建产物）
- 默认双架构推送（`linux/amd64,linux/arm64`）
- 默认双标签（`latest` + 版本标签）
- 内置重试与 manifest 校验，降低网络抖动影响

## 最小用法

```bash
scripts/docker-release.sh \
  --image docker.ainas.cc:5200/namespace/app
```

默认会用当前 git short SHA 作为版本标签，并同时推 `latest`。

## 推荐用法（先编译再推镜像）

```bash
scripts/docker-release.sh \
  --image docker.ainas.cc:5200/namespace/app \
  --build-cmd "<你的编译命令>" \
  --artifact <产物路径>
```

`--artifact` 用于验证构建产物确实变化（避免“代码更新但镜像复用旧产物”）。

## registry-web 示例（当前项目）

```bash
scripts/docker-release.sh \
  --image docker.ainas.cc:5200/ainas/registry-web \
  --build-cmd "docker run --rm -v $PWD:/work -w /work gradle:7.6.4-jdk8 bash -lc './grailsw clean && ./grailsw -Dgrails.env=production war && cp -f target/docker-registry-web-0.1.3-SNAPSHOT.war ROOT.war'" \
  --artifact ROOT.war \
  --attempts 6 \
  --sleep 20
```

## 常见参数

- `--version-tag <tag>`：自定义版本标签（默认 git short SHA）
- `--platforms <list>`：默认 `linux/amd64,linux/arm64`
- `--no-latest`：仅推版本标签
- `--verify-pull`：推送成功后再 `docker pull` 验证

## 注意

- Registry UI 里的时间通常是 **镜像构建时间（Created）**，不一定是 push 时间。
- 若你希望“时间/内容明确更新”，请确保构建产物已更新（如 WAR hash 改变）。
- 网络不稳定时脚本会自动重试。
