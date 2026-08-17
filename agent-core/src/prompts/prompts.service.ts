/**
 * PromptsService — 加载仓库根 prompts/*.yaml 并渲染 {{占位符}}
 *
 * 文档：docs/PROMPTS-guide.md · docs/工程架构.md §9.3
 *
 * 文案真源在 `{CHATFLOWS_ROOT}/prompts/`；本服务只做读盘 + 组装，
 * 不决定闸门 / 下一槽 / template_id。
 */

import { Injectable, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as fs from 'fs';
import * as path from 'path';
import { parse as parseYaml } from 'yaml';
import { TraceService } from '../common/trace.service';
import { renderPrompt } from './render';

export interface ChatMessages {
  system: string;
  user: string;
}

interface PurposeBlock {
  system?: string;
  system_suffix?: string;
  user: string;
}

interface ReceptionistDoc {
  style: string;
  echo_industry: PurposeBlock;
  echo_goals: PurposeBlock;
  structure_brief: PurposeBlock;
  polish_summary: PurposeBlock;
  detail_prompts: PurposeBlock;
  normalize_industry: PurposeBlock;
  brief_template: PurposeBlock;
  extract_detail: PurposeBlock;
  revise_patch: PurposeBlock;
}

interface SystemUserDoc {
  system: string;
  user: string;
}

@Injectable()
export class PromptsService implements OnModuleInit {
  private receptionist!: ReceptionistDoc;
  private router!: SystemUserDoc;
  private matcher!: SystemUserDoc;

  constructor(
    private readonly config: ConfigService,
    private readonly trace: TraceService,
  ) {}

  onModuleInit(): void {
    this.receptionist = this.readYaml<ReceptionistDoc>('receptionist.yaml');
    this.requireFields('receptionist', this.receptionist, [
      'style',
      'echo_industry',
      'echo_goals',
      'structure_brief',
      'polish_summary',
      'detail_prompts',
      'normalize_industry',
      'brief_template',
      'extract_detail',
      'revise_patch',
    ]);
    this.router = this.readYaml<SystemUserDoc>('router.yaml');
    this.requireFields('router', this.router, ['system', 'user']);
    this.matcher = this.readYaml<SystemUserDoc>('matcher.yaml');
    this.requireFields('matcher', this.matcher, ['system', 'user']);

    this.trace.step('Prompts', 'loaded', {
      root: this.root,
      promptsDir: this.promptsDir,
      files: ['receptionist.yaml', 'router.yaml', 'matcher.yaml'],
    });
  }

  get root(): string {
    const configured = this.config.get<string>('CHATFLOWS_ROOT');
    if (configured && configured.trim()) {
      return path.resolve(configured.trim());
    }
    return path.resolve(__dirname, '../../..');
  }

  get promptsDir(): string {
    return path.join(this.root, 'prompts');
  }

  // ── receptionist ──────────────────────────────────────────────

  echoIndustry(industryName: string): ChatMessages {
    return this.receptionistWithStyle('echo_industry', { industryName });
  }

  echoGoals(goalLines: string): ChatMessages {
    return this.receptionistWithStyle('echo_goals', { goalLines });
  }

  structureBrief(args: {
    industryName: string;
    rawBrief: string;
  }): ChatMessages {
    return this.receptionistWithStyle('structure_brief', args);
  }

  polishSummary(summaryJson: string): ChatMessages {
    return this.receptionistWithStyle('polish_summary', { summaryJson });
  }

  detailPrompts(summaryJson: string): ChatMessages {
    return this.receptionistWithStyleAndSuffix('detail_prompts', {
      summaryJson,
    });
  }

  normalizeIndustry(args: {
    freeText: string;
    optionsLines: string;
  }): ChatMessages {
    const block = this.receptionist.normalize_industry;
    if (!block.system) {
      throw new Error('prompts/receptionist.yaml normalize_industry.system missing');
    }
    return {
      system: block.system,
      user: renderPrompt(
        block.user,
        args,
        'receptionist.normalize_industry.user',
      ),
    };
  }

  briefTemplate(vars: {
    industryName: string;
    goalLines: string;
  }): ChatMessages {
    return this.receptionistWithStyleAndSuffix('brief_template', vars);
  }

  extractDetail(vars: {
    industryName: string;
    detailJson: string;
    text: string;
  }): ChatMessages {
    return this.receptionistWithStyleAndSuffix('extract_detail', vars);
  }

  revisePatch(vars: {
    industryId: string;
    industryName: string;
    goalIdsJson: string;
    brief: string;
    detailJson: string;
    industriesLines: string;
    goalsLines: string;
    text: string;
  }): ChatMessages {
    return this.receptionistWithStyleAndSuffix('revise_patch', vars);
  }

  // ── router / matcher ──────────────────────────────────────────

  routerMessages(args: {
    utterance: string;
    scenesText: string;
    tenantText: string;
  }): ChatMessages {
    return {
      system: this.router.system,
      user: renderPrompt(this.router.user, args, 'router.user'),
    };
  }

  matcherMessages(args: {
    triageJson: string;
    tenantJson: string;
    candidatesText: string;
  }): ChatMessages {
    return {
      system: this.matcher.system,
      user: renderPrompt(this.matcher.user, args, 'matcher.user'),
    };
  }

  // ── internals ─────────────────────────────────────────────────

  private receptionistWithStyle(
    purpose:
      | 'echo_industry'
      | 'echo_goals'
      | 'structure_brief'
      | 'polish_summary',
    vars: Record<string, string>,
  ): ChatMessages {
    const block = this.receptionist[purpose];
    const label = `receptionist.${purpose}`;
    return {
      system: this.receptionist.style,
      user: renderPrompt(block.user, vars, `${label}.user`),
    };
  }

  private receptionistWithStyleAndSuffix(
    purpose:
      | 'detail_prompts'
      | 'brief_template'
      | 'extract_detail'
      | 'revise_patch',
    vars: Record<string, string>,
  ): ChatMessages {
    const block = this.receptionist[purpose];
    const label = `receptionist.${purpose}`;
    const suffix = block.system_suffix ?? '';
    return {
      system: `${this.receptionist.style}\n\n${suffix}`,
      user: renderPrompt(block.user, vars, `${label}.user`),
    };
  }

  private readYaml<T>(fileName: string): T {
    const filePath = path.join(this.promptsDir, fileName);
    if (!fs.existsSync(filePath)) {
      throw new Error(`prompts file missing: ${filePath}`);
    }
    const raw = fs.readFileSync(filePath, 'utf8');
    return parseYaml(raw) as T;
  }

  private requireFields(
    name: string,
    doc: object,
    keys: string[],
  ): void {
    const record = doc as Record<string, unknown>;
    for (const key of keys) {
      if (record[key] === undefined || record[key] === null) {
        throw new Error(`prompts/${name}.yaml missing field: ${key}`);
      }
    }
  }
}
