# Atlas 运营工作台

面向企业风险排查运营人员的对话式前端。页面直接连接
`../service`，不在浏览器内复制风险规则或工作流判断。

## 页面

- `/`：持久化多轮对话、原报告上传、任务卡片和建议动作；
- `/tasks`：任务筛选、工作区状态、证据确认与排除；
- `/reports`：正式 DOCX 报告中心；
- `/capabilities`：Skills、数据源和运行边界；
- `/settings`：后端地址、运营人员标识和冰川/墨玉主题。

默认后端地址为 `http://localhost:8080`。也可以在系统设置中修改，
设置仅保存在当前浏览器。

```powershell
npm run dev
npm test
```

构建时可用 `NEXT_PUBLIC_ATLAS_API_BASE` 指定默认后端地址；运营人员在页面中保存的地址
优先级更高。当前页面使用 `X-Operator-Id` 作为开发期身份，生产接入统一身份前不得把它
视为安全认证。

完整操作说明见 `../design/Atlas运营操作手册V1.md`。
