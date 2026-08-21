FROM node:22.22-alpine AS build
WORKDIR /app
ARG NEXT_PUBLIC_ATLAS_API_BASE=http://localhost:8080
ENV NEXT_PUBLIC_ATLAS_API_BASE=${NEXT_PUBLIC_ATLAS_API_BASE}
COPY console/package.json console/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY console ./
RUN npm run build

FROM node:22.22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app ./
EXPOSE 3000
CMD ["npm", "start", "--", "--host", "0.0.0.0", "--port", "3000"]
