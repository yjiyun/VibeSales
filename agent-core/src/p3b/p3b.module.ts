import { Module } from '@nestjs/common'; import { P3bService } from './p3b.service';
@Module({ providers: [P3bService], exports: [P3bService] }) export class P3bModule {}
