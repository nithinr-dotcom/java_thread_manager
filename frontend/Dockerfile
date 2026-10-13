# syntax=docker/dockerfile:1.7
# Store UI (POS, kitchen display, back office) served by nginx, same origin as the API.
FROM node:22.11.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build

FROM nginx:1.27.3-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8443
