FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

RUN apk add --no-cache postgresql-client jq

COPY src ./src
COPY scripts ./scripts
COPY migrations ./migrations
COPY config ./config
COPY docker-entrypoint.sh ./docker-entrypoint.sh

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["./docker-entrypoint.sh"]
