/**
 * CatalogsService — 全局词表（catalogs）加载与查询
 *
 * 文档：docs/CATALOGS-guide.md · docs/产品设计.md §8 · docs/工程架构.md §9.1
 *
 * 词表含：scenes / agent_families / industries / channels / connectors /
 * capabilities / **business_goals**（向导业务目标，≠ scenes）。
 */

import { Injectable, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as fs from 'fs';
import * as path from 'path';
import { parse as parseYaml } from 'yaml';
import { TraceService } from '../common/trace.service';
import {
  BusinessGoalItem,
  CatalogOption,
  SceneCatalogItem,
  SceneSlot,
} from '../common/types';

export interface CatalogsBundle {
  scenes: SceneCatalogItem[];
  sceneIds: Set<string>;
  agentFamilies: Set<string>;
  industries: Set<string>;
  channels: Set<string>;
  channelDefault: string;
  connectors: Set<string>;
  capabilities: Set<string>;
  /** 业务目标完整条目（含 capability_hints 等） */
  businessGoals: BusinessGoalItem[];
  businessGoalIds: Set<string>;
  /**
   * 按词表名保存选项，供向导 next_ask.options。
   * key ∈ industries | agent_families | capabilities | channels | connectors | business_goals
   */
  options: Record<string, CatalogOption[]>;
}

const SOURCE_TO_OPTIONS: Record<string, string> = {
  industries: 'industries',
  agent_families: 'agent_families',
  capabilities: 'capabilities',
  channels: 'channels',
  connectors: 'connectors',
  business_goals: 'business_goals',
};

@Injectable()
export class CatalogsService implements OnModuleInit {
  private bundle!: CatalogsBundle;

  constructor(
    private readonly config: ConfigService,
    private readonly trace: TraceService,
  ) {}

  onModuleInit(): void {
    this.bundle = this.loadAll();
    this.trace.step('Catalogs', 'loaded', {
      root: this.root,
      catalogsDir: this.catalogsDir,
      scenes: this.bundle.scenes.map((s) => s.id),
      channelDefault: this.bundle.channelDefault,
      counts: {
        scenes: this.bundle.sceneIds.size,
        agentFamilies: this.bundle.agentFamilies.size,
        industries: this.bundle.industries.size,
        channels: this.bundle.channels.size,
        connectors: this.bundle.connectors.size,
        capabilities: this.bundle.capabilities.size,
        businessGoals: this.bundle.businessGoalIds.size,
      },
    });
  }

  get root(): string {
    const configured = this.config.get<string>('CHATFLOWS_ROOT');
    if (configured && configured.trim()) {
      return path.resolve(configured.trim());
    }
    return path.resolve(__dirname, '../../..');
  }

  get catalogsDir(): string {
    return path.join(this.root, 'catalogs');
  }

  get(): CatalogsBundle {
    return this.bundle;
  }

  sceneById(id: string): SceneCatalogItem | undefined {
    return this.bundle.scenes.find((s) => s.id === id);
  }

  businessGoalById(id: string): BusinessGoalItem | undefined {
    return this.bundle.businessGoals.find((g) => g.id === id);
  }

  optionsFor(source?: string): CatalogOption[] {
    if (!source) return [];
    const key = SOURCE_TO_OPTIONS[source] ?? source;
    return this.bundle.options[key] ?? [];
  }

  /** 向导 S1 用的 7 档行业：每档 group 一条，展示名为 group。细项 id 仍合法，供自由描述归一与 scene 硬过滤。 */
  wizardIndustryOptions(): CatalogOption[] {
    return (this.bundle.options.industries ?? [])
      .filter((o) => o.wizard === true)
      .map((o) => ({
        ...o,
        name: o.group || o.name,
      }));
  }

  industryGroup(industryId: string): string | undefined {
    return this.bundle.options.industries?.find((o) => o.id === industryId)
      ?.group;
  }

  /** 选中的 industry id 是否覆盖该 scene（含同 group 细项，如 cluster 点选 vs beauty 装机）。 */
  sceneCoversIndustry(
    scene: Pick<SceneCatalogItem, 'industries'>,
    industryId: string,
  ): boolean {
    const ids = scene.industries ?? [];
    if (ids.includes(industryId)) return true;
    const group = this.industryGroup(industryId);
    if (!group) return false;
    const members = new Set(
      (this.bundle.options.industries ?? [])
        .filter((o) => o.group === group)
        .map((o) => o.id),
    );
    return ids.some((id) => members.has(id));
  }

  /** 行业选项按 group 分组（保持 yaml 顺序）。 */
  industriesGrouped(): Array<{ group: string; options: CatalogOption[] }> {
    const opts = this.bundle.options.industries ?? [];
    const order: string[] = [];
    const map = new Map<string, CatalogOption[]>();
    for (const o of opts) {
      const g = o.group ?? '其他';
      if (!map.has(g)) {
        map.set(g, []);
        order.push(g);
      }
      map.get(g)!.push(o);
    }
    return order.map((group) => ({ group, options: map.get(group)! }));
  }

  capabilityNames(ids: string[]): string[] {
    return this.namesOf('capabilities', ids);
  }

  /** 连接器 id → 中文名（P2 归因文案用，缺失时退回 id）。 */
  connectorNames(ids: string[]): string[] {
    return this.namesOf('connectors', ids);
  }

  /** 渠道 id → 中文名（批量）。 */
  channelNames(ids: string[]): string[] {
    return this.namesOf('channels', ids);
  }

  /** 渠道 id → 中文名。 */
  channelName(id?: string): string {
    if (!id) return '';
    return this.namesOf('channels', [id])[0];
  }

  /** 场景 id → 中文名（scenes.yaml；未收录时退回 id）。 */
  sceneName(id?: string): string {
    if (!id) return '';
    return this.sceneById(id)?.name ?? id;
  }

  /**
   * 场景 id → 给用户看的名字。
   *
   * 与 `sceneName` 的区别只在未收录时：那是 P1 判出的词表外场景（risk_flags
   * 含 out_of_catalog），把英文 id 摆到界面上等于把内部标识泄给用户，
   * 所以退回一句中性说法。工程层文案仍用 `sceneName`。
   */
  sceneNameForUser(id?: string): string {
    if (!id) return '你描述的场景';
    return this.sceneById(id)?.name ?? '你描述的场景';
  }

  /** 某词表下 id → name 批量翻译，未收录的原样返回。 */
  private namesOf(source: string, ids: string[]): string[] {
    const map = new Map(
      (this.bundle.options[source] ?? []).map((o) => [o.id, o.name]),
    );
    return ids.map((id) => map.get(id) ?? id);
  }

  private loadAll(): CatalogsBundle {
    const dir = this.catalogsDir;
    const scenesDoc = this.readYaml(path.join(dir, 'scenes.yaml'));
    const familiesDoc = this.readYaml(path.join(dir, 'agent_families.yaml'));
    const industriesDoc = this.readYaml(path.join(dir, 'industries.yaml'));
    const channelsDoc = this.readYaml(path.join(dir, 'channels.yaml'));
    const connectorsDoc = this.readYaml(path.join(dir, 'connectors.yaml'));
    const capabilitiesDoc = this.readYaml(path.join(dir, 'capabilities.yaml'));
    const goalsDoc = this.readYaml(path.join(dir, 'business_goals.yaml'));

    const scenes: SceneCatalogItem[] = (scenesDoc.scenes ?? []).map(
      (s: Record<string, unknown>) => ({
        id: String(s.id),
        name: String(s.name ?? s.id),
        summary: String(s.summary ?? ''),
        agent_family: s.agent_family ? String(s.agent_family) : undefined,
        industries: Array.isArray(s.industries)
          ? s.industries.map(String)
          : [],
        typical_prompts: Array.isArray(s.typical_prompts)
          ? s.typical_prompts.map(String)
          : [],
        status: s.status ? String(s.status) : undefined,
        slots: this.slotsFrom(s.slots),
        ask_order: Array.isArray(s.ask_order)
          ? s.ask_order.map(String)
          : undefined,
      }),
    );

    const businessGoals: BusinessGoalItem[] = (
      goalsDoc.business_goals ?? []
    ).map((g: Record<string, unknown>) => ({
      id: String(g.id),
      name: String(g.name ?? g.id),
      description: g.description ? String(g.description) : undefined,
      capability_hints: Array.isArray(g.capability_hints)
        ? g.capability_hints.map(String)
        : [],
      agent_family_hint: g.agent_family_hint
        ? String(g.agent_family_hint)
        : undefined,
      summary_role_hint: g.summary_role_hint
        ? String(g.summary_role_hint)
        : undefined,
    }));

    const channelDefault = String(channelsDoc.default ?? 'wecom');

    return {
      scenes,
      sceneIds: new Set(scenes.map((s) => s.id)),
      agentFamilies: this.idsFrom(familiesDoc.families),
      industries: this.idsFrom(industriesDoc.industries),
      channels: this.idsFrom(channelsDoc.channels),
      channelDefault,
      connectors: this.idsFrom(connectorsDoc.connectors),
      capabilities: this.idsFrom(capabilitiesDoc.capabilities),
      businessGoals,
      businessGoalIds: new Set(businessGoals.map((g) => g.id)),
      options: {
        industries: this.optionsFromDoc(industriesDoc.industries),
        agent_families: this.optionsFromDoc(familiesDoc.families),
        channels: this.optionsFromDoc(channelsDoc.channels),
        connectors: this.optionsFromDoc(connectorsDoc.connectors),
        capabilities: this.optionsFromDoc(capabilitiesDoc.capabilities),
        business_goals: businessGoals.map((g) => ({
          id: g.id,
          name: g.name,
          description: g.description,
        })),
      },
    };
  }

  private slotsFrom(v: unknown): SceneSlot[] | undefined {
    if (!Array.isArray(v)) return undefined;
    return v.map((raw) => {
      const s = raw as Record<string, unknown>;
      return {
        key: String(s.key),
        label: s.label ? String(s.label) : undefined,
        source: s.source ? String(s.source) : undefined,
        required: Boolean(s.required),
        multi: Boolean(s.multi),
        why: s.why ? String(s.why) : undefined,
      };
    });
  }

  private optionsFromDoc(items: unknown): CatalogOption[] {
    if (!Array.isArray(items)) return [];
    return items
      .map((raw) => {
        const it = raw as Record<string, unknown>;
        const id = String(it.id ?? '').trim();
        if (!id) return null;
        return {
          id,
          name: String(it.name ?? id),
          description: it.description ? String(it.description) : undefined,
          group: it.group ? String(it.group) : undefined,
          wizard: it.wizard === true,
        } as CatalogOption;
      })
      .filter((o): o is CatalogOption => o !== null);
  }

  private idsFrom(items: unknown): Set<string> {
    if (!Array.isArray(items)) return new Set();
    return new Set(
      items.map((it) => String((it as { id: string }).id)).filter(Boolean),
    );
  }

  private readYaml(filePath: string): Record<string, any> {
    if (!fs.existsSync(filePath)) {
      throw new Error(`Catalog file missing: ${filePath}`);
    }
    const raw = fs.readFileSync(filePath, 'utf8');
    return parseYaml(raw) as Record<string, any>;
  }
}
