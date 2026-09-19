# 多币种轧差清算工作台（Clearing Netting Workbench）

单币种多边轧差清算全栈演示：录入义务 → 执行轧差 → 查看净头寸 → 确认 settle。

## How to Run

```bash
cd projects/01-clearing-netting
docker compose up --build
```

镜像默认走 `docker.m.daocloud.io` 与 Maven/npm 国内源，便于在受限网络下构建。若本机已有同名官方镜像亦可直接使用。

后台运行：

```bash
docker compose up --build -d
```

停止：

```bash
docker compose down
```

## Services

| 服务 | 宿主机地址 |
|------|------------|
| Frontend | http://localhost:3171 |
| Backend API | http://localhost:8171 |
| PostgreSQL | localhost:54371 |

容器内：backend 监听 `8080`，frontend nginx 将 `/api` 反代到 `backend:8080`。

## 测试账号

| 用户名 | 密码 | 权限 |
|--------|------|------|
| operator | op123456 | 可写（轧差、settle、新建会员/义务） |
| viewer | view123456 | 只读 |

## Verification

1. 打开 http://localhost:3171 ，使用 `operator` / `op123456` 登录
2. 首页查看 seed 灌入的待轧差义务摘要与最近批次
3. 「会员」页确认演示会员为 ACTIVE；可新建或启停
4. 「义务」页筛选 OPEN 义务，或新建一笔同币种义务
5. 「轧差执行」选择 settleDate + currency（如 USD），执行轧差
   - 点击后批次先落库为 **RUNNING**，进度区依次展示服务端持久化的三个阶段：
     **校验 → 计算 → 落库**；历史批次列表同步显示 `RUNNING · 校验/计算/落库`
   - 执行中刷新页面或重开轧差页，仍能从进度区/列表看到进行中（按后端状态恢复），不是前端假 loading
6. 成功后批次进入 **COMPLETED**，进度区显示净头寸表且 ΣnetAmount = 0，可点「查看详情」；可进入批次详情点击 Settle，义务变为 SETTLED
7. 失败时（如同交割日/币种重复执行，已无 OPEN 义务）：进度区变为错误信息，列表为 **FAILED**，失败原因与后端 `failureReason` 一致，且不会弹出成功提示
8. 批次执行期间服务端重启，残留的进行中批次会在启动时自动判定为 FAILED（原因：执行被中断），不会永久卡在 RUNNING
9. 使用 `viewer` 登录，确认只能浏览、无法执行写操作

健康检查：

```bash
curl http://localhost:8171/api/health
```

登录：

```bash
curl -X POST http://localhost:8171/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"operator\",\"password\":\"op123456\"}"
```

## 技术栈

- Backend: Java 17、Spring Boot 3、Hexagonal、JPA、PostgreSQL、JWT
- Frontend: Vue 3、Vite、Element Plus、Pinia、Vue Router、nginx
- Infra: Docker Compose（db / backend / seed / frontend）

## 项目结构

```
01-clearing-netting/
├── PRD.md
├── README.md
├── docker-compose.yml
├── backend/
├── frontend/
└── seed/
```
