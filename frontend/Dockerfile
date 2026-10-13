# syntax=docker/dockerfile:1.7
# Store UI (POS, kitchen display, back office) served by nginx, same origin as the API.
FROM node:22.11.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
# Demo builds only (root docker-compose.yml): the UI signs in with this key by itself. Empty in production.
ARG VITE_DEMO_API_KEY=
RUN VITE_DEMO_API_KEY="$VITE_DEMO_API_KEY" npm run build

FROM nginx:1.27.3-alpine
ARG NGINX_CONF=nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
COPY ${NGINX_CONF} /etc/nginx/conf.d/default.conf
EXPOSE 8443
