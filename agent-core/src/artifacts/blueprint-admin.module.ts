import { Module } from '@nestjs/common';
import { BlueprintAdminController } from './blueprint-admin.controller';
@Module({ controllers:[BlueprintAdminController] }) export class BlueprintAdminModule {}
