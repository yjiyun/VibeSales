#!/usr/bin/env node
// AgentLoop P0 探针：不碰业务代码，手拼标准 OTLP/HTTP JSON 发一棵最小 GenAI span 树，
// 验证 P0-1（AI Agent 面板能否识别 invoke_agent/chat + 按 gen_ai.session.id 聚合）与 P0-6（连通/鉴权）。
//
// 用法：
//   ARMS_LICENSE_KEY=xxxx node scripts/agentloop-p0/otlp-probe.mjs
// 可选覆盖：OTEL_EXPORTER_OTLP_TRACES_ENDPOINT / OTEL_SERVICE_NAME / AGENTLOOP_WORKSPACE / AGENTLOOP_PROJECT
//
// 成功判据：HTTP 2xx，然后去 AgentLoop 控制台「AI Agent 可观测」按下方打印的 trace-id / session.id 查。

import { randomBytes, randomUUID } from "node:crypto";

const ENDPOINT =
  process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT ||
  "https://proj-xtrace-3922099f398c18d91efc45571ab29-cn-guangzhou.cn-guangzhou.log.aliyuncs.com/apm/trace/opentelemetry/v1/traces";
const WORKSPACE = process.env.AGENTLOOP_WORKSPACE || "agentloop-7f371f9a844483cf38ba3e84bc46add5";
const PROJECT = process.env.AGENTLOOP_PROJECT || "proj-xtrace-3922099f398c18d91efc45571ab29-cn-guangzhou";
const SERVICE_NAME = process.env.OTEL_SERVICE_NAME || "vibe-sales-p0-probe";
const LICENSE_KEY = process.env.ARMS_LICENSE_KEY;

if (!LICENSE_KEY) {
  console.error("[probe] ARMS_LICENSE_KEY 未设置。请从密钥服务注入后重试：");
  console.error("        ARMS_LICENSE_KEY=<key> node scripts/agentloop-p0/otlp-probe.mjs");
  process.exit(2);
}

const hex = (n) => randomBytes(n).toString("hex");
const nowNano = () => String(BigInt(Date.now()) * 1_000_000n);

const traceId = hex(16); // 32 hex
const rootSpanId = hex(8); // 16 hex
const childSpanId = hex(8);
const runId = randomUUID(); // = gen_ai.session.id = agentteams.run_id
const clientCode = "p0_probe";

const startNano = nowNano();
const endNano = String(BigInt(startNano) + 1_500_000_000n); // +1.5s

const attr = (key, value) => {
  if (typeof value === "number" && Number.isInteger(value)) return { key, value: { intValue: String(value) } };
  if (typeof value === "boolean") return { key, value: { boolValue: value } };
  return { key, value: { stringValue: String(value) } };
};

// 父：invoke_agent（一次搭建根）
const rootSpan = {
  traceId,
  spanId: rootSpanId,
  name: "invoke_agent",
  kind: 1, // INTERNAL
  startTimeUnixNano: startNano,
  endTimeUnixNano: endNano,
  attributes: [
    attr("gen_ai.operation.name", "invoke_agent"),
    attr("gen_ai.system", "dashscope"),
    attr("gen_ai.session.id", runId),
    attr("gen_ai.agent.name", SERVICE_NAME),
    attr("gen_ai.input.messages", JSON.stringify([{ role: "user", parts: [{ type: "text", content: "P0 探针：帮我搭建一个客服智能体" }] }])),
    attr("gen_ai.output.messages", JSON.stringify([{ role: "assistant", parts: [{ type: "text", content: "好的，已完成 P0 探针链路验证" }], finish_reason: "stop" }])),
    attr("agentteams.run_id", runId),
    attr("agentteams.client_code", clientCode),
    attr("agentteams.phase", "P1"),
    attr("agentteams.agent", "orchestrator"),
  ],
  status: { code: 1 }, // OK
};

// 子：chat（模型调用）
const childSpan = {
  traceId,
  spanId: childSpanId,
  parentSpanId: rootSpanId,
  name: "chat",
  kind: 1,
  startTimeUnixNano: String(BigInt(startNano) + 100_000_000n),
  endTimeUnixNano: String(BigInt(endNano) - 100_000_000n),
  attributes: [
    attr("gen_ai.operation.name", "chat"),
    attr("gen_ai.system", "dashscope"),
    attr("gen_ai.session.id", runId),
    attr("gen_ai.request.model", "qwen-plus"),
    attr("gen_ai.usage.input_tokens", 42),
    attr("gen_ai.usage.output_tokens", 18),
    attr("agentteams.run_id", runId),
    attr("agentteams.client_code", clientCode),
  ],
  status: { code: 1 },
};

const payload = {
  resourceSpans: [
    {
      resource: {
        attributes: [
          attr("service.name", SERVICE_NAME),
          attr("service.version", "v0.1.0"),
          attr("deployment.environment", "p0-probe"),
          attr("acs.cms.workspace", WORKSPACE),
          attr("acs.arms.service.feature", "genai_app"),
          attr("gen_ai.instrumentation.sdk.name", "loongsuite-genai-utils"),
        ],
      },
      scopeSpans: [
        { scope: { name: "agentloop.p0.probe", version: "1.0.0" }, spans: [rootSpan, childSpan] },
      ],
    },
  ],
};

const headers = {
  "content-type": "application/json",
  "x-arms-license-key": LICENSE_KEY,
  "x-arms-project": PROJECT,
  "x-cms-workspace": WORKSPACE,
};

// 脱敏打印：只打 host / workspace / service，不打 license / headers。
const host = new URL(ENDPOINT).host;
console.log("[probe] endpoint host :", host);
console.log("[probe] workspace     :", WORKSPACE);
console.log("[probe] service.name  :", SERVICE_NAME);
console.log("[probe] trace-id      :", traceId);
console.log("[probe] session.id    :", runId, "(= run_id，控制台按它聚合)");
console.log("[probe] spans         : invoke_agent(root) -> chat(child)");
console.log("[probe] 发送中 ...");

try {
  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(15000),
  });
  const text = await res.text().catch(() => "");
  console.log(`[probe] HTTP ${res.status} ${res.statusText}`);
  if (text) console.log("[probe] resp body:", text.slice(0, 500));
  if (res.status >= 200 && res.status < 300) {
    console.log("");
    console.log("[probe] ✅ 上报成功。现在去 AgentLoop 控制台 → AI Agent 可观测：");
    console.log(`        1) 应用列表能否看到 service = ${SERVICE_NAME}`);
    console.log(`        2) 按 trace-id ${traceId} 或 session ${runId} 查`);
    console.log("        3) 关键：能否解析出 Agent(invoke_agent) + Chat(chat) 节点、Input/Output 非空、token 可见");
    console.log("        —— 这三点全成立 = P0-1 通过，OTLP 路线成立；否则触发 §7 的协议重估开关。");
  } else if (res.status === 415) {
    console.log("[probe] ⚠️ 415：端点可能只收 OTLP/protobuf 不收 JSON。改用 protobuf 版探针（见 README 备选）。");
  } else {
    console.log("[probe] ⚠️ 非 2xx：检查 license/workspace/project 是否匹配，或参数拼写（genai_app）。");
  }
} catch (err) {
  console.error("[probe] ❌ 发送失败：", err?.name, err?.message);
  console.error("        先确认网络可达（curl 该 endpoint 应回 405），再确认凭证。");
  process.exit(1);
}
