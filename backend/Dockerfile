# Build stage
FROM node:20-slim AS builder

WORKDIR /app

# Install build tools required to compile native dependencies (like better-sqlite3)
RUN apt-get update && apt-get install -y python3 make g++ && rm -rf /var/lib/apt/lists/*

COPY package*.json ./
RUN npm install

COPY tsconfig.json ./
COPY src/ ./src/

# Compile TypeScript
RUN npm run build

# Remove development dependencies to keep production image small
RUN npm prune --production

# Production stage
FROM node:20-slim AS runner

WORKDIR /app

COPY package*.json ./
# Copy compiled Javascript code
COPY --from=builder /app/dist ./dist
# Copy pre-compiled production node_modules
COPY --from=builder /app/node_modules ./node_modules
# Ingested SQLite database
COPY stops.db ./stops.db

ENV PORT=3000
EXPOSE 3000

CMD ["npm", "start"]
