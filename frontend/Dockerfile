# Build stage
FROM node:24-alpine AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install dependencies
RUN npm ci --legacy-peer-deps

# Copy source code
COPY . .

# API base URL baked into the bundle at build time. Empty (default) = same origin: the SPA calls
# the host it was loaded from (/api/...), which a reverse proxy must forward to the backend.
# Never default this to http://localhost:8080: in a visitor's browser that is the visitor's own
# machine (Local Network Access prompts, "Network Error", mixed content behind https).
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

# Build the application
RUN npm run build

# Production stage: static file server for the built SPA (vite itself is a
# devDependency and is not part of this image)
FROM node:24-alpine

WORKDIR /app

COPY --from=builder /app/dist ./dist

# serve 14.2.6 pins compression 1.8.1 (CVE-2026-87776, fixed in 1.8.2): override it. Nothing needs
# npm, npx, yarn or corepack at run time, and their bundled libraries carry vulnerabilities of their
# own, so they are removed from the image after the install.
RUN npm install -g serve@14.2.6 \
    && npm install --prefix="$(npm root -g)/serve" --omit=dev --no-save --no-audit --no-fund compression@1.8.2 \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-* \
       /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg

# Create non-root user for security
RUN addgroup -g 1001 -S nodejs && adduser -S nodejs -u 1001
# Numeric UID/GID so the user resolves on any host (hadolint DL3066)
USER 1001:1001

EXPOSE 5173

# -s: single-page-app fallback to index.html for client-side routes
CMD ["serve", "-s", "dist", "-l", "5173"]
