# 只打包宿主机已经 vite build 好的 dist。不要在这里 npm ci。
# 刷新入口：./scripts/refresh-agentteams-console.sh
FROM nginx:1.27.0-alpine

COPY agent-console/nginx.conf /etc/nginx/conf.d/default.conf
COPY agent-console/dist /usr/share/nginx/html

EXPOSE 8080
