FROM rust:1-bookworm AS builder
WORKDIR /usr/src/moji-cloud-server

COPY Cargo.toml Cargo.lock ./
COPY src ./src

RUN cargo build --release --locked

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY --from=builder /usr/src/moji-cloud-server/target/release/moji-cloud-server /usr/local/bin/moji-cloud-server

VOLUME ["/data"]
ENV MOJI_BIND_ADDR=0.0.0.0:8787
ENV MOJI_DATA_DIR=/data
EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8787/api/v1/health || exit 1

ENTRYPOINT ["/usr/local/bin/moji-cloud-server"]
