import { Module } from '@nestjs/common'; import { P3cService } from './p3c.service'; import { SkillCatalogService } from './skill-catalog.service';
@Module({ providers: [P3cService,SkillCatalogService], exports: [P3cService] }) export class P3cModule {}
