FROM node:22-slim

WORKDIR /app

ENV NODE_ENV=production

COPY package*.json ./

RUN if [ -f package-lock.json ]; then npm ci --omit=dev; else npm install --omit=dev; fi

COPY src ./src
COPY scripts ./scripts

RUN mkdir -p /app/data && chown -R node:node /app
USER node

EXPOSE 8080

CMD ["node", "src/index.js"]
