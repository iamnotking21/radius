# devops/ — MEMORY
owner: devops-tencent
CARVE-OUTS: devops/ci/ test+gate stages → qa-test · devops/tofu/coturn/ → calling-webrtc
(you own the CI runner + infra/deploy stages, and review coturn's tofu before apply)

tofu/ ansible/ compose/ k8s/ ci/ runbooks/   (see carve-outs above)

## cloud
Tencent CVM. REGION SINGAPORE. never mainland (ICP filing + GFW).
P0: cvm-edge 2c4g(public) · cvm-core 4c8g(private) · cvm-data 4c16g(private)
1 VPC. private subnet default. deny-by-default security groups. no public DB port EVER.
bastion via SSH CA, no long-lived keys.

## path
Compose(P0) → K3s(P1-2) → TKE(P3+). every step containerised + tofu-defined.
NEVER configure a host by hand. if it's not in tofu/ansible it does not exist.

## stack
Caddy(auto TLS) · SOPS+age → OpenBao(P2) · Gitea+Actions+registry ·
Prometheus Grafana Loki Tempo GlitchTip UptimeKuma · pgBackRest

## backup rule
nightly full + continuous WAL → SeaweedFS. PLUS weekly encrypted copy OFF TENCENT.
never keep only backup at only provider. restore drill monthly, logged.

## CI gates (fail the build)
tests · govulncheck · osv-scanner · gosec · semgrep · buf breaking ·
BLE conformance vectors both platforms · battery regression · latency regression
