import { Global, Module } from '@nestjs/common';
import { ArtifactStoreService } from './artifact-store.service';
import { ApprovalProofService } from '../common/approval-proof.service';
@Global()
@Module({ providers: [ArtifactStoreService,ApprovalProofService], exports: [ArtifactStoreService,ApprovalProofService] })
export class ArtifactStoreModule {}
