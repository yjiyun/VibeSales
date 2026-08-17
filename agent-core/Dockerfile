FROM node:22.14.0-bookworm-slim AS build
WORKDIR /build/agent-core
COPY agent-core/package.json agent-core/package-lock.json ./
RUN npm ci
COPY agent-core/tsconfig.json ./
COPY agent-core/src ./src
COPY agent-core/scripts ./scripts
COPY agent-core/sql ./sql
RUN npm run build

FROM build AS tools
ENTRYPOINT ["npm"]
CMD ["run", "db:init"]

# NOTE: 本 stage 依赖 VIBE_SALES_BASE 提供的 agentteams-resources/、
# worker-packages/、docs/agentteams/ 三个目录，单独克隆本仓库时无法构建。
# 需在平台 monorepo 根目录执行 docker build --target verifier。
FROM build AS verifier
WORKDIR /app
COPY --from=build /build/agent-core /app/agent-core
COPY scripts /app/scripts
COPY agentteams-resources /app/agentteams-resources
COPY worker-packages /app/worker-packages
COPY docs/agentteams /app/docs/agentteams
ENTRYPOINT ["node"]
CMD ["scripts/preflight-agentteams-integration.js"]

FROM node:22.14.0-bookworm-slim AS runtime
ENV NODE_ENV=production CHATFLOWS_ROOT=/app
WORKDIR /app/agent-core
COPY agent-core/package.json agent-core/package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY --from=build /build/agent-core/dist ./dist
COPY agent-core/fixtures ./fixtures
COPY catalogs /app/catalogs
COPY prompts /app/prompts
COPY flows /app/flows
RUN mkdir -p /tmp/agent-console-dist && chown node:node /tmp/agent-console-dist
USER node
EXPOSE 3100 3101
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=12 CMD ["node", "-e", "fetch('http://127.0.0.1:3100/healthz').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"]
CMD ["node", "dist/main-mcp.js"]
