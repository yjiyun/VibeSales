import { Module } from '@nestjs/common'; import { P4Service } from './p4.service'; import { FlowPlatformClient } from './flow-platform.client'; import { AgentRuntimeClient } from './agent-runtime.client';
@Module({ providers: [P4Service,FlowPlatformClient,AgentRuntimeClient], exports: [P4Service] }) export class P4Module {}
