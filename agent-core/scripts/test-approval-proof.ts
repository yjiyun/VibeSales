import 'reflect-metadata';
import * as fs from 'fs';
import { ApprovalProofService } from '../src/common/approval-proof.service';
const file=process.env.APPROVAL_PROOF_FILE?.trim();if(!file)throw new Error('APPROVAL_PROOF_FILE required');process.env.PIPELINE_APPROVAL_SIGNING_SECRET=process.env.APPROVAL_PROOF_SECRET;const decoded=new ApprovalProofService().verify(fs.readFileSync(file,'utf8').trim());if(decoded.run_id!=='550e8400-e29b-41d4-a716-446655440000'||decoded.approval_id!=='approval-cross-language'||decoded.actor!=='@admin:local'||decoded.decision!=='APPROVE')throw new Error('Java/Nest proof contract mismatch');process.stdout.write('[PASS] Nest verified Java HMAC approval proof\n');
