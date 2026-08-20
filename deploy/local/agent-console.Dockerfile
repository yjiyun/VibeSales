FROM nginx:1.27.0-alpine

COPY deploy/local/agent-console.nginx.conf /etc/nginx/conf.d/default.conf
COPY agent-console/dist /usr/share/nginx/html

EXPOSE 8080
