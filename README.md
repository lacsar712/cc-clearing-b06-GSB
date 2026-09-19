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
6. 确认净头寸表 ΣnetAmount = 0，批次状态 COMPLETED
7. 进入批次详情，点击 Settle，义务变为 SETTLED
8. 使用 `viewer` 登录，确认只能浏览、无法执行写操作

### 会员历史净头寸

seed 会构造可核对的多批次历史：同一交割日 USD 两个 COMPLETED 批次（批次 A 已 settle、批次 B 未 settle），次日还有一个 EUR 批次；另建一个不参与任何批次的会员 Delta Holdings。

1. 「会员」页或左侧「会员历史净头寸」入口进入查询页，选择会员（如 Alpha Bank）
2. 页面按【交割日 + 币种】分行展示该会员在**所有 COMPLETED（含已 settle）批次**中的净头寸代数和
3. 展开汇总行可见每个来源批次的 Run ID（可点进批次详情）、创建时间、是否已 SETTLED、该批次净头寸；各来源净头寸代数和等于汇总行，并与批次详情页逐笔一致
4. 选择 Delta Holdings 等无历史会员显示空表（不报错）
5. 页面顶部固定展示汇总口径说明；operator 与 viewer 均可查询，端口不变

接口：`GET /api/members/{id}/position-history`（只读，双角色可访问；返回 member、aggregationRule 及按日/币分行的 rows，每行含 sources 可追溯到批次）。


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
