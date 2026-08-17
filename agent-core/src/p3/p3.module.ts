import { Module } from '@nestjs/common'; import { P3Service } from './p3.service';
@Module({ providers: [P3Service], exports: [P3Service] }) export class P3Module {}
