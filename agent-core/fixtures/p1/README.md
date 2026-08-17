# [P1] Fixtures — 美妆顾问 / 招聘助手

用于验证粗补闸门逻辑完备性（**不调 Match、无需 API Key**）。

```bash
cd agent-core
npm run test:p1
# 单条
npm run cli -- p1 --quiet --client-code acme_beauty \
  --triage fixtures/p1/beauty/pass-full.json --expect-gate PASS
```

## 场景对照

| 场景 | scene_id | 必填粗槽（scenes.yaml） |
|------|----------|-------------------------|
| 美妆顾问 | `beauty_wecom_cs` | industry, role, **desired_capabilities** |
| 招聘助手 | `hr_recruit_wecom_faq` | industry, role（能力选填） |

## 用例索引

见 `manifest.json`（`npm run test:p1` 按此跑）。

| id | 期望 gate | 覆盖点 |
|----|-----------|--------|
| beauty/pass-full | PASS | 三槽齐 + 高置信 |
| beauty/ask-missing-role | ASK | 缺 role → next_ask=role |
| beauty/ask-missing-industry | ASK | 缺 industry |
| beauty/ask-missing-capabilities | ASK | 美妆必填能力未齐 |
| beauty/ask-low-confidence | ASK | 槽齐但置信不足 |
| beauty/slots-pass | PASS | --slots 推断 scene |
| beauty/slots-ask-no-caps | ASK | slots 缺能力 |
| recruit/pass-full | PASS | 招聘满槽 |
| recruit/pass-without-capabilities | PASS | 能力选填可空 |
| recruit/ask-missing-role | ASK | 缺角色 |
| recruit/slots-pass | PASS | --slots 推断 |
| custom/unknown-scene | CUSTOM | 非法 scene_id |
| custom/empty-scene | CUSTOM | 空 scene |

`utterance-*.txt` 供 P2 `match --utterance`（需 Key）或人工对照，不纳入 `test:p1`。
