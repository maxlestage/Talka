# Talka : un seul binaire, le serveur Paquets, avec le site et le navigateur
# (Rust compilé en WebAssembly) rangés dedans.
#
#   docker build -t talka . && docker run --rm -p 8080:8080 talka

FROM rust:1-slim-bookworm AS build
RUN rustup target add wasm32-unknown-unknown
WORKDIR /src
COPY . .
RUN cargo xtask web && cargo build --release --bin talka && cp target/release/talka /talka

FROM debian:bookworm-slim
RUN useradd --system --uid 10001 talka
COPY --from=build /talka /usr/local/bin/talka
USER talka
ENV PORT=8080
EXPOSE 8080
CMD ["talka"]
