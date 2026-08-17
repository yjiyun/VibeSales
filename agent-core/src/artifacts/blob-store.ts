import { createHash } from 'crypto';
import { Client } from 'minio';

export interface BlobRef {
  $blob: { bucket: string; key: string; sha256: string; contentType: string };
}

/** MinIO 大对象层；键不可变，PostgreSQL artifact.payload 只存可审计引用。 */
export class BlobStore {
  private readonly client: Client;
  private readonly bucket: string;
  private ready?: Promise<void>;

  constructor() {
    const endpoint = process.env.MINIO_ENDPOINT?.trim();
    const accessKey = process.env.MINIO_ACCESS_KEY?.trim();
    const secretKey = process.env.MINIO_SECRET_KEY?.trim();
    if (!endpoint || !accessKey || !secretKey) {
      throw new Error('MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY are required for postgres artifact storage');
    }
    const url = new URL(endpoint.includes('://') ? endpoint : 'http://' + endpoint);
    this.bucket = process.env.MINIO_BUCKET?.trim() || 'chatflows-artifacts';
    this.client = new Client({
      endPoint: url.hostname,
      port: Number(url.port || (url.protocol === 'https:' ? 443 : 80)),
      useSSL: url.protocol === 'https:',
      accessKey,
      secretKey,
    });
  }

  async put(runId: string, kind: string, version: number, payload: unknown): Promise<BlobRef> {
    await this.ensureBucket();
    const body = Buffer.from(JSON.stringify(payload));
    const sha256 = createHash('sha256').update(body).digest('hex');
    const key = 'runs/' + runId + '/' + kind + '/v' + version + '-' + sha256.slice(0, 16) + '.json';
    await this.client.putObject(this.bucket, key, body, body.length, { 'Content-Type': 'application/json', 'x-amz-meta-sha256': sha256 });
    return { $blob: { bucket: this.bucket, key, sha256, contentType: 'application/json' } };
  }

  async get<T>(ref: BlobRef): Promise<T> {
    const stream = await this.client.getObject(ref.$blob.bucket, ref.$blob.key);
    const chunks: Buffer[] = [];
    for await (const chunk of stream) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
    const body = Buffer.concat(chunks);
    const actual = createHash('sha256').update(body).digest('hex');
    if (actual !== ref.$blob.sha256) throw new Error('MinIO artifact checksum mismatch: ' + ref.$blob.key);
    return JSON.parse(body.toString('utf8')) as T;
  }

  async health():Promise<boolean>{try{return await this.client.bucketExists(this.bucket);}catch{return false;}}

  private ensureBucket(): Promise<void> {
    if (!this.ready) this.ready = (async () => {
      if (!await this.client.bucketExists(this.bucket)) await this.client.makeBucket(this.bucket);
    })();
    return this.ready;
  }
}

export function isBlobRef(value: unknown): value is BlobRef {
  const blob = (value as BlobRef | undefined)?.$blob;
  return Boolean(blob?.bucket && blob?.key && blob?.sha256);
}
