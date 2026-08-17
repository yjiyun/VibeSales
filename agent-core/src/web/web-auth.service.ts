import { HttpException, Injectable } from '@nestjs/common';
import { timingSafeEqual } from 'crypto';

export interface WebPrincipal { actor: string; role: string; clientCode: string }
interface Credential { token: string; clientCode: string; actor?: string; roles: string[] }

@Injectable()
export class WebAuthService {
  private readonly credentials = this.loadCredentials();
  require(headers: Record<string, string | string[] | undefined>): WebPrincipal {
    if (!this.credentials.length) throw new HttpException('wizard authentication disabled', 503);
    const supplied = this.header(headers, 'authorization').replace(/^Bearer\s+/i, '');
    const credential = this.credentials.find(item => this.equal(item.token, supplied));
    if (!credential) throw new HttpException('unauthorized', 401);
    const role = this.header(headers, 'x-role');
    if (!credential.roles.includes(role)) throw new HttpException('role not allowed', 403);
    const actor = this.required(this.header(headers, 'x-actor'), 'X-Actor');
    if (credential.actor && credential.actor !== actor) throw new HttpException('actor does not match credential', 403);
    return { actor, role, clientCode: credential.clientCode };
  }
  private loadCredentials(): Credential[] {
    const raw = process.env.WEB_AUTH_CREDENTIALS?.trim();
    if (raw) {
      let parsed: unknown; try { parsed = JSON.parse(raw); } catch { throw new Error('WEB_AUTH_CREDENTIALS must be valid JSON'); }
      if (!Array.isArray(parsed)) throw new Error('WEB_AUTH_CREDENTIALS must be a JSON array');
      const credentials = parsed.map((item, index) => this.parseCredential(item, index));
      if (new Set(credentials.map(item => item.token)).size !== credentials.length) throw new Error('WEB_AUTH_CREDENTIALS contains duplicate tokens');
      return credentials;
    }
    const token = process.env.WEB_AUTH_TOKEN?.trim(), clientCode = process.env.WEB_AUTH_CLIENT_CODE?.trim();
    if (!token && !clientCode) return [];
    if (!token || !clientCode) throw new Error('WEB_AUTH_TOKEN and WEB_AUTH_CLIENT_CODE must be configured together');
    return [{ token: this.validToken(token), clientCode: this.validClientCode(clientCode), roles: ['user', 'admin'] }];
  }
  private parseCredential(value: unknown, index: number): Credential {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('WEB_AUTH_CREDENTIALS['+index+'] must be an object');
    const item = value as Record<string, unknown>, roles = Array.isArray(item.roles) ? item.roles.map(String) : ['user', 'admin'];
    if (!roles.length || roles.some(role => !/^[a-z][a-z0-9_-]*$/.test(role))) throw new Error('WEB_AUTH_CREDENTIALS['+index+'].roles is invalid');
    const actor = typeof item.actor === 'string' && item.actor.trim() ? item.actor.trim() : undefined;
    return { token: this.validToken(String(item.token ?? '')), clientCode: this.validClientCode(String(item.client_code ?? '')), actor, roles: [...new Set(roles)] };
  }
  private validToken(value: string) { if (value.length < 16) throw new Error('wizard auth token must be at least 16 characters'); return value; }
  private validClientCode(value: string) { if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error('wizard auth client_code is invalid'); return value; }
  private equal(expected: string, supplied: string) { const a=Buffer.from(expected),b=Buffer.from(supplied);return a.length===b.length&&timingSafeEqual(a,b); }
  private header(headers: Record<string,string|string[]|undefined>,name:string){const value=headers[name];return Array.isArray(value)?value[0]??'':value??'';}
  private required(value:string,name:string){if(!value.trim())throw new HttpException(name+' required',400);return value.trim();}
}
