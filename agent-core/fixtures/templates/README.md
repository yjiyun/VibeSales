# fixtures/templates — 验证用模板包（不是可交付资产）

真实资产目录（`{CHATFLOWS_ROOT}/flows/`）下同一 scene 目前只有一个包，第一道硬过滤按 scene
精确匹配，所以 `passed ≤ 1` → Decide 永远走 1 候选分支。「2+ 候选」那一路
（千问裁决 `via=qwen`、`diffs`、Match 卡的 `alternatives` 落选区）在真实数据下走不到。

这里的包只为把候选数顶到 2，让那条路可跑可看。**默认不加载**，要显式打开：

```bash
TEMPLATE_EXTRA_ROOTS=fixtures/templates \
  npm run cli -- match --client-code acme_beauty --triage fixtures/triage-guyude.json
```

约定与真实包一致（`flows/Chatflow-*/meta.yaml` + `BRIEF.md`，枚举值必须 ∈ catalogs），
差别只有三处：

- 没有 `coze_export`：不指向任何 workflow，选中了也开通不了，避免被当成真包用
- `search_text` / BRIEF 里刻意不出现 `beauty`、`faq_retrieve` 这类英文 id，
  让它在关键词打分上低于真包，稳定地落在「落选」区而不是抢走命中位
- `stability: draft`，不靠稳定性分插队

改动这里不影响真实资产目录，也不影响任何不带 `TEMPLATE_EXTRA_ROOTS` 的运行。
