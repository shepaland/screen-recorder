FROM node:20-alpine AS build
WORKDIR /app
COPY web-dashboard/package.json web-dashboard/package-lock.json* ./
RUN npm ci
COPY web-dashboard/ .
RUN npm run build

FROM nginx:1.25-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY deploy/docker/web-dashboard/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
