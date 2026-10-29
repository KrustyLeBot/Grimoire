# Static binary on an empty image: a few MB on disk, about 10 MB of RAM.
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/reddit-proxy .

FROM scratch
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /out/reddit-proxy /reddit-proxy
ENV DATA_DIR=/data GOMEMLIMIT=40MiB
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s --retries=3 CMD ["/reddit-proxy", "-health"]
ENTRYPOINT ["/reddit-proxy"]
