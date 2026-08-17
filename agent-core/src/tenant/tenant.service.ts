/**
 * TenantService — 按 client_code 加载租户画像
 *
 * ## 文档
 * - `docs/工程架构.md` §5.4 多租户上下文、§4 硬约束 C1/C2
 *
 * ## 在整条链路中的位置
 * ```text
 * CLI --client-code <code> [--tenant <path>]
 *         │
 *         ▼
 * TenantService.resolve  ◄── 本文件
 *         │  fixtures/tenants/<code>.json
 *         ▼
 * RequestContext → Intent / Match（硬过滤用 channels & connectors）
 * ```
 *
 * 缺文件或 JSON 内 client_code 与入参不一致 → 抛错（多租户隔离硬约束）。
 */

import { Injectable } from '@nestjs/common';
import * as fs from 'fs';
import * as path from 'path';
import { TraceService } from '../common/trace.service';
import { TenantProfile } from '../common/types';

@Injectable()
export class TenantService {
  constructor(private readonly trace: TraceService) {}

  /**
   * 解析租户。
   * @param clientCode 必填 SaaS 编码
   * @param tenantPathOverride 可选：覆盖默认 fixtures 路径（CLI --tenant）
   */
  resolve(clientCode: string, tenantPathOverride?: string): TenantProfile {
    const code = (clientCode ?? '').trim();
    if (!code) {
      throw new Error('client_code is required');
    }

    const filePath = tenantPathOverride
      ? path.resolve(tenantPathOverride)
      : path.join(this.fixturesDir, 'tenants', `${code}.json`);

    this.trace.step('Tenant', 'resolve.start', {
      client_code: code,
      path: filePath,
      override: Boolean(tenantPathOverride),
    });

    if (!fs.existsSync(filePath)) {
      throw new Error(`Tenant profile not found: ${filePath}`);
    }

    const raw = JSON.parse(fs.readFileSync(filePath, 'utf8')) as TenantProfile;
    if (!raw.client_code || raw.client_code !== code) {
      throw new Error(
        `Tenant JSON client_code mismatch: file=${raw.client_code} expected=${code}`,
      );
    }

    const profile: TenantProfile = {
      client_code: raw.client_code,
      channels: Array.isArray(raw.channels) ? raw.channels.map(String) : [],
      connectors: Array.isArray(raw.connectors)
        ? raw.connectors.map(String)
        : [],
    };

    this.trace.step('Tenant', 'resolve.done', profile);
    return profile;
  }

  /** DEMO 租户 fixtures 根目录：`agent-core/fixtures`。 */
  private get fixturesDir(): string {
    return path.resolve(__dirname, '../../fixtures');
  }
}
