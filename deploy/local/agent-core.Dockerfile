FROM node:22.14.0-bookworm-slim

ENV NODE_ENV=production CHATFLOWS_ROOT=/app
WORKDIR /app/agent-core

COPY agent-core/package.json agent-core/package-lock.json ./
COPY agent-core/node_modules ./node_modules
COPY agent-core/dist ./dist
COPY agent-core/fixtures ./fixtures
COPY agent-core/scripts ./scripts
COPY agent-core/sql ./sql
COPY scripts /app/local-scripts
COPY catalogs /app/catalogs
COPY prompts /app/prompts
COPY flows /app/flows

RUN mkdir -p /tmp/agent-console-dist && chown -R node:node /app /tmp/agent-console-dist

USER node
EXPOSE 3100 3101
CMD ["node", "dist/main-mcp.js"]
