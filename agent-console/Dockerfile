# 镜像内 npm ci 编前端。本机 15173 不要走这条（registry 会 401），用
# deploy/agentteams/agent-console.Dockerfile + scripts/refresh-agentteams-console.sh。
FROM node:22.14.0-bookworm-slim AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html vite.config.js ./
COPY src ./src
# Vite 把这些值打进 import.meta.env；只传 ORCHESTRATION_MODE 会落到
# manual-dev-*-token，对不上 Nest/manager，欢迎屏直接 401 unauthorized。
ARG ORCHESTRATION_MODE=platform
ARG WEB_AUTH_TOKEN
ARG WEB_AUTH_CLIENT_CODE
ARG PIPELINE_CONTROL_TOKEN
ARG RUNTIME_AUTH_TOKEN
ARG RUNTIME_ADMIN_TOKEN
ARG MANAGER_AUTH_TOKEN
ARG MANAGER_ADMIN_TOKEN
ARG AGENTTEAMS_HUMAN_IDS
ENV ORCHESTRATION_MODE=$ORCHESTRATION_MODE \
    WEB_AUTH_TOKEN=$WEB_AUTH_TOKEN \
    WEB_AUTH_CLIENT_CODE=$WEB_AUTH_CLIENT_CODE \
    PIPELINE_CONTROL_TOKEN=$PIPELINE_CONTROL_TOKEN \
    RUNTIME_AUTH_TOKEN=$RUNTIME_AUTH_TOKEN \
    RUNTIME_ADMIN_TOKEN=$RUNTIME_ADMIN_TOKEN \
    MANAGER_AUTH_TOKEN=$MANAGER_AUTH_TOKEN \
    MANAGER_ADMIN_TOKEN=$MANAGER_ADMIN_TOKEN \
    AGENTTEAMS_HUMAN_IDS=$AGENTTEAMS_HUMAN_IDS
RUN npm run build

FROM nginx:1.27.0-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
