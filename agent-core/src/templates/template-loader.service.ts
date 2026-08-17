/**
 * TemplateLoaderService — 扫盘加载 Coze 模板包 → 内存 `TemplateRecord[]`
 *
 * ## 文档
 * - `docs/META-yaml-guide.md`：meta 字段约定（历史别名 mate.yaml 仍兼容）
 * - `docs/工程架构.md` §9.2 模板资产、§4 硬约束 C4/C5/C6
 *
 * ## 在整条链路中的位置
 * ```text
 * CatalogsService（词表已就绪）
 *         │
 *         ▼
 * TemplateLoaderService  ◄── 本文件：启动时扫 flows/Chatflow-*
 *         │                   读 meta.yaml（兼容 mate.yaml）+ BRIEF.md
 *         │                   校验枚举 ∈ catalogs（C4）；channels 空 → wecom（C5）
 *         │                   不读 workflow/*.yaml 正文（C6，留给开通阶段）
 *         ▼
 * MatchService → Filter → Rank → Decide
 * ```
 *
 * ## 模板包约定
 * `<CHATFLOWS_ROOT>/flows/Chatflow-<name>/`
 *   - `meta.yaml`（亦兼容 `mate.yaml`；缺则跳过该目录，如 sample 包）
 *   - `BRIEF.md`（可选；缺则 brief 空串）
 *   - `workflow/*.yaml`（仅通过 meta.coze_export 记路径，不加载正文）
 *
 * ## 附加模板根（`TEMPLATE_EXTRA_ROOTS`，默认空）
 * 真实资产目录下同一 scene 目前只有一个包，Decide 的「2+ 候选」分支（千问裁决、
 * `diffs`、`alternatives` 落选区）在真实数据下走不到。该开关按逗号分隔追加若干目录，
 * 每个目录同样按 `Chatflow-*` 扫，用于喂 `fixtures/templates/` 下的验证用包：
 *
 * ```bash
 * TEMPLATE_EXTRA_ROOTS=fixtures/templates npm run cli -- match ...
 * ```
 *
 * 相对路径按进程工作目录（agent-core 项目根）解析。目录不存在直接抛错——配置了
 * 却没生效比不配置更危险（会让人误以为在验多候选路径）。真实根优先：附加根里
 * 出现同名 `template_id` 时跳过并告警，不会顶掉线上资产。
 */

import { Injectable, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as fs from 'fs';
import * as path from 'path';
import { parse as parseYaml } from 'yaml';
import { CatalogsService } from '../catalogs/catalogs.service';
import { TraceService } from '../common/trace.service';
import { Stability, TemplateParam, TemplateRecord } from '../common/types';

/** DEMO 允许的稳定性枚举；非法值在加载期直接失败。 */
const ALLOWED_STABILITY = new Set<Stability>(['draft', 'beta', 'ga']);

@Injectable()
export class TemplateLoaderService implements OnModuleInit {
  private templates: TemplateRecord[] = [];

  constructor(
    private readonly catalogs: CatalogsService,
    private readonly config: ConfigService,
    private readonly trace: TraceService,
  ) {}

  /**
   * Nest 启动钩子：在 CLI 命令前完成扫盘。
   * 流程位置：Catalogs.onModuleInit → 本方法 → 之后才可 Match/list。
   * 无可用模板则抛错，避免空库静默匹配。
   */
  onModuleInit(): void {
    this.templates = this.scanAll();
    this.trace.step('TemplateLoader', 'scan.done', {
      count: this.templates.length,
      roots: [path.join(this.catalogs.root, 'flows'), ...this.extraRoots()],
      templates: this.templates.map((t) => ({
        template_id: t.template_id,
        scene_ids: t.scene_ids,
        channels: t.channels,
        connectors_required: t.connectors_required,
        stability: t.stability,
        brief_chars: t.brief.length,
      })),
    });
  }

  /**
   * 返回已加载模板列表（只读引用）。
   * 调用方：MatchService（过滤输入）、CLI `--list-templates`。
   */
  list(): TemplateRecord[] {
    return this.templates;
  }

  /**
   * 真实根 + 附加根依次扫盘并去重。
   * 真实根必须至少有一个可用包（空库静默匹配是事故）；附加根允许为空目录，
   * 但目录本身不存在时抛错，避免「配了没生效」被当成验证通过。
   */
  private scanAll(): TemplateRecord[] {
    const flowsRoot = path.join(this.catalogs.root, 'flows');
    const out = this.scan(flowsRoot);
    if (out.length < 1) {
      throw new Error(
        `No Chatflow-* templates with meta.yaml under ${flowsRoot}`,
      );
    }

    const seen = new Set(out.map((t) => t.template_id));
    for (const extra of this.extraRoots()) {
      if (!fs.existsSync(extra) || !fs.statSync(extra).isDirectory()) {
        throw new Error(
          `TEMPLATE_EXTRA_ROOTS entry is not a directory: ${extra}`,
        );
      }
      for (const t of this.scan(extra)) {
        // 真实根优先：附加根不得覆盖同 id 的线上资产
        if (seen.has(t.template_id)) {
          this.trace.step('TemplateLoader', 'warn.extra_root_duplicate', {
            template_id: t.template_id,
            skipped_root: t.root_path,
          });
          continue;
        }
        seen.add(t.template_id);
        out.push(t);
      }
    }
    return out;
  }

  /**
   * `TEMPLATE_EXTRA_ROOTS`（逗号分隔）→ 绝对路径数组，默认空。
   * 相对路径按进程 cwd 解析，便于 `TEMPLATE_EXTRA_ROOTS=fixtures/templates` 直接用。
   */
  private extraRoots(): string[] {
    const raw = this.config.get<string>('TEMPLATE_EXTRA_ROOTS') ?? '';
    return raw
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map((s) => path.resolve(s));
  }

  /**
   * 扫一个根下的 `Chatflow-*` 目录，组装 `TemplateRecord`。
   * 无 meta.yaml（或 mate.yaml）的包直接跳过（不报错），保证 sample 等半成品不挡 DEMO。
   * 根目录不存在时返回空数组（由 scanAll 决定是否抛错）。
   */
  private scan(root: string): TemplateRecord[] {
    if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
      return [];
    }
    const entries = fs.readdirSync(root, { withFileTypes: true });
    const out: TemplateRecord[] = [];

    for (const ent of entries) {
      if (!ent.isDirectory() || !ent.name.startsWith('Chatflow-')) continue;
      const packRoot = path.join(root, ent.name);
      const metaPath = this.findMetaPath(packRoot);
      if (!metaPath) continue;

      const briefPath = path.join(packRoot, 'BRIEF.md');
      const brief = fs.existsSync(briefPath)
        ? fs.readFileSync(briefPath, 'utf8')
        : '';

      const meta = parseYaml(fs.readFileSync(metaPath, 'utf8')) as Record<
        string,
        any
      >;
      out.push(this.toRecord(packRoot, meta, brief));
    }

    return out;
  }

  /** 优先 `meta.yaml`，兼容历史别名 `mate.yaml`。 */
  private findMetaPath(packRoot: string): string | null {
    for (const name of ['meta.yaml', 'mate.yaml']) {
      const p = path.join(packRoot, name);
      if (fs.existsSync(p)) return p;
    }
    return null;
  }

  /**
   * meta + BRIEF → `TemplateRecord`。
   * - 规范化 channels（空 → catalogs.channelDefault，通常 wecom）
   * - 枚举字段必须 ∈ CatalogsService（C4），否则 throw
   * - `meta_summary` 供 Decide/千问选型时附短摘要，避免塞全文 meta
   */
  private toRecord(
    rootPath: string,
    meta: Record<string, any>,
    brief: string,
  ): TemplateRecord {
    const cats = this.catalogs.get();
    const templateId = String(meta.template_id ?? '').trim();
    if (!templateId) {
      throw new Error(`Missing template_id in ${rootPath}`);
    }

    const sceneIds = this.asStringArray(meta.scene_ids);
    const industries = this.asStringArray(meta.industries);
    let channels = this.asStringArray(meta.channels);
    // C5：空渠道不当「无约束」，回填词表 default
    if (channels.length === 0) {
      channels = [cats.channelDefault];
    }
    const capabilities = this.asStringArray(meta.capabilities);
    const connectorsRequired = this.asStringArray(meta.connectors_required);
    const connectorsOptional = this.asStringArray(meta.connectors_optional);
    const agentFamily = meta.agent_family
      ? String(meta.agent_family)
      : undefined;
    const stabilityRaw = String(meta.stability ?? 'draft') as Stability;
    if (!ALLOWED_STABILITY.has(stabilityRaw)) {
      throw new Error(
        `Invalid stability "${stabilityRaw}" for ${templateId}; expect draft|beta|ga`,
      );
    }

    this.assertInSet(sceneIds, cats.sceneIds, 'scene_ids', templateId);
    if (agentFamily) {
      this.assertInSet([agentFamily], cats.agentFamilies, 'agent_family', templateId);
    }
    this.assertInSet(industries, cats.industries, 'industries', templateId);
    this.assertInSet(channels, cats.channels, 'channels', templateId);
    this.assertInSet(capabilities, cats.capabilities, 'capabilities', templateId);
    this.assertInSet(
      connectorsRequired,
      cats.connectors,
      'connectors_required',
      templateId,
    );
    this.assertInSet(
      connectorsOptional,
      cats.connectors,
      'connectors_optional',
      templateId,
    );

    const params: TemplateParam[] = Array.isArray(meta.params)
      ? meta.params.map((p: Record<string, unknown>) => ({
          key: String(p.key),
          label: p.label ? String(p.label) : undefined,
          required: Boolean(p.required),
          default: p.default,
          example:
            p.example !== undefined && p.example !== null
              ? String(p.example)
              : undefined,
        }))
      : [];

    // v2 治理告警（META-yaml-guide 硬约束）：required param 必须带 example
    for (const p of params) {
      if (p.required && !p.example) {
        this.trace.step('TemplateLoader', 'warn.param_missing_example', {
          template_id: templateId,
          param: p.key,
        });
      }
    }

    const cozeExport = meta.coze_export
      ? String(meta.coze_export)
      : undefined;

    return {
      template_id: templateId,
      display_name: String(meta.display_name ?? templateId),
      stability: stabilityRaw,
      scene_ids: sceneIds,
      agent_family: agentFamily,
      industries,
      channels,
      capabilities,
      connectors_required: connectorsRequired,
      connectors_optional: connectorsOptional,
      search_text: String(meta.search_text ?? ''),
      brief,
      root_path: rootPath,
      coze_export: cozeExport,
      params,
      meta_summary: {
        template_id: templateId,
        scene_ids: sceneIds,
        channels,
        capabilities,
        connectors_required: connectorsRequired,
        agent_family: agentFamily,
        industries,
        stability: stabilityRaw,
      },
    };
  }

  private asStringArray(v: unknown): string[] {
    if (!Array.isArray(v)) return [];
    return v.map(String);
  }

  /** 约束 C4：meta 枚举值必须落在 catalogs 已登记 id 内。 */
  private assertInSet(
    values: string[],
    allowed: Set<string>,
    field: string,
    templateId: string,
  ): void {
    for (const v of values) {
      if (!allowed.has(v)) {
        throw new Error(
          `Template ${templateId}: ${field} value "${v}" not in catalogs`,
        );
      }
    }
  }
}
