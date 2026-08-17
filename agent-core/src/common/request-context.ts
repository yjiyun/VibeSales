/**
 * RequestContext 工厂
 *
 * 租户解析成功后，把 TenantProfile 提升为请求级上下文（含 request_id）。
 * 流程位置：CLI → TenantService.resolve → buildRequestContext → Intent/Match。
 */

import { randomUUID } from 'crypto';
import { RequestContext, TenantProfile } from './types';

/** 构建请求上下文；未传 requestId 时自动生成 UUID。 */
export function buildRequestContext(
  tenant: TenantProfile,
  requestId?: string,
): RequestContext {
  return {
    client_code: tenant.client_code,
    tenant,
    request_id: requestId ?? randomUUID(),
  };
}
