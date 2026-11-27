#!/bin/bash
#
# Jaws RPC Samples launcher script
#
# Usage: ./run-sample.sh <command> [options]
#

set -e

# The port probing below uses bash's /dev/tcp pseudo-device, and the progress
# output relies on bash's echo/printf behaviour. `sh run-sample.sh` gets a POSIX
# shell in which both quietly misbehave (echo -n prints a literal "-n", and a
# failed probe looks like a provider problem), so re-exec instead.
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MVN="./mvnw"
BENCHMARK_MODULE="jaws-samples/jaws-sample-benchmark"
INJVM_MODULE="jaws-samples/jaws-sample-injvm"
PROVIDER_MODULE="jaws-samples/jaws-sample-zk-provider"
CONSUMER_MODULE="jaws-samples/jaws-sample-zk-consumer"
NACOS_PROVIDER_MODULE="jaws-samples/jaws-sample-nacos-provider"
NACOS_CONSUMER_MODULE="jaws-samples/jaws-sample-nacos-consumer"
NETTY_PROVIDER_MODULE="jaws-samples/jaws-sample-netty-provider"
NETTY_CONSUMER_MODULE="jaws-samples/jaws-sample-netty-consumer"
HTTP2_PROVIDER_MODULE="jaws-samples/jaws-sample-http2-provider"
HTTP2_CONSUMER_MODULE="jaws-samples/jaws-sample-http2-consumer"
WIRE_PROVIDER_MODULE="jaws-samples/jaws-sample-wire-provider"
WIRE_CONSUMER_MODULE="jaws-samples/jaws-sample-wire-consumer"
WIRE_INTEROP_MODULE="jaws-samples/jaws-sample-wire-interop"
ADAPTIVE_PROVIDER_MODULE="jaws-samples/jaws-sample-adaptive-provider"
ADAPTIVE_CONSUMER_MODULE="jaws-samples/jaws-sample-adaptive-consumer"
HARBOR_BOOTSTRAP_MODULE="jaws-harbor"
HARBOR_PROVIDER_MODULE="jaws-samples/jaws-sample-harbor-provider"
HARBOR_CONSUMER_MODULE="jaws-samples/jaws-sample-harbor-consumer"
# Application-level service discovery (local metadata mode) against Nacos.
APP_PROVIDER_MODULE="jaws-samples/jaws-sample-app-provider"
APP_CONSUMER_MODULE="jaws-samples/jaws-sample-app-consumer"
# The same app-level discovery demo against the harbor registry leg.
APP2_PROVIDER_MODULE="jaws-samples/jaws-sample-app2-provider"
APP2_CONSUMER_MODULE="jaws-samples/jaws-sample-app2-consumer"
# The same app-level discovery demo against the zookeeper registry leg.
APP3_PROVIDER_MODULE="jaws-samples/jaws-sample-app3-provider"
APP3_CONSUMER_MODULE="jaws-samples/jaws-sample-app3-consumer"
# The same HarborServer, reached through the nacos leg (real nacos-client).
HARBORX_PROVIDER_MODULE="jaws-samples/jaws-sample-harborx-provider"
HARBORX_CONSUMER_MODULE="jaws-samples/jaws-sample-harborx-consumer"
# Persistent-instance (Raft CP) demo against the same HarborServer.
RAFT_PROVIDER_MODULE="jaws-samples/jaws-sample-raft-provider"
RAFT_CONSUMER_MODULE="jaws-samples/jaws-sample-raft-consumer"
# The same Raft CP path, reached through the nacos leg (real nacos-client).
RAFTX_PROVIDER_MODULE="jaws-samples/jaws-sample-raftx-provider"
RAFTX_CONSUMER_MODULE="jaws-samples/jaws-sample-raftx-consumer"
# RocketMQ-style storage engine practice (jaws-store) over the jaws protocol.
STORE_MODULE="jaws-samples/jaws-sample-store"

INJVM_MAIN="org.hongxi.jaws.sample.injvm.InjvmRpcDemo"
PROVIDER_MAIN="org.hongxi.jaws.sample.zk.provider.ZkProvider"
CONSUMER_MAIN="org.hongxi.jaws.sample.zk.consumer.ZkConsumer"
BENCHMARK_MAIN="org.hongxi.jaws.sample.benchmark.JawsBenchmark"
NACOS_PROVIDER_MAIN="org.hongxi.jaws.sample.nacos.provider.NacosProvider"
NACOS_CONSUMER_MAIN="org.hongxi.jaws.sample.nacos.consumer.NacosConsumer"
NETTY_PROVIDER_MAIN="org.hongxi.jaws.sample.netty.provider.NettyProvider"
NETTY_CONSUMER_MAIN="org.hongxi.jaws.sample.netty.consumer.NettyConsumer"
HTTP2_PROVIDER_MAIN="org.hongxi.jaws.sample.http2.provider.Http2Provider"
HTTP2_CONSUMER_MAIN="org.hongxi.jaws.sample.http2.consumer.Http2Consumer"
WIRE_PROVIDER_MAIN="org.hongxi.jaws.sample.wire.provider.WireProvider"
WIRE_CONSUMER_MAIN="org.hongxi.jaws.sample.wire.consumer.WireConsumer"
WIRE_BENCHMARK_MAIN="org.hongxi.jaws.sample.benchmark.WireBenchmark"
GRPC_BENCHMARK_MAIN="org.hongxi.jaws.sample.benchmark.GrpcBenchmark"
WIRE_INTEROP_GRPC_CALL_WIRE="org.hongxi.jaws.sample.wire.interop.GrpcCallWireDemo"
WIRE_INTEROP_WIRE_CALL_GRPC="org.hongxi.jaws.sample.wire.interop.WireCallGrpcDemo"
WIRE_INTEROP_GRPC_SERVER="org.hongxi.jaws.sample.wire.interop.GrpcServerMain"
WIRE_INTEROP_HEALTH="org.hongxi.jaws.sample.wire.interop.WireHealthDemo"
WIRE_INTEROP_MANAGED="org.hongxi.jaws.sample.wire.interop.ManagedChannelDemo"
WIRE_INTEROP_KEEPALIVE="org.hongxi.jaws.sample.wire.interop.WireKeepaliveDemo"
WIRE_INTEROP_SERVER_INTERCEPTOR="org.hongxi.jaws.sample.wire.interop.WireInterceptorDemo"
WIRE_INTEROP_CLIENT_INTERCEPTOR="org.hongxi.jaws.sample.wire.interop.WireClientInterceptorDemo"
WIRE_INTEROP_GOAWAY="org.hongxi.jaws.sample.wire.interop.WireGoAwayDemo"
ADAPTIVE_PROVIDER_MAIN="org.hongxi.jaws.sample.adaptive.provider.AdaptiveProvider"
ADAPTIVE_CONSUMER_MAIN="org.hongxi.jaws.sample.adaptive.consumer.AdaptiveConsumer"
HARBOR_BOOTSTRAP_MAIN="org.hongxi.jaws.harbor.HarborBootstrap"
HARBOR_PROVIDER_MAIN="org.hongxi.jaws.sample.harbor.provider.HarborProvider"
HARBOR_CONSUMER_MAIN="org.hongxi.jaws.sample.harbor.consumer.HarborConsumer"
APP_PROVIDER_MAIN="org.hongxi.jaws.sample.app.provider.AppProvider"
APP_CONSUMER_MAIN="org.hongxi.jaws.sample.app.consumer.AppConsumer"
APP2_PROVIDER_MAIN="org.hongxi.jaws.sample.app2.provider.App2Provider"
APP2_CONSUMER_MAIN="org.hongxi.jaws.sample.app2.consumer.App2Consumer"
APP3_PROVIDER_MAIN="org.hongxi.jaws.sample.app3.provider.App3Provider"
APP3_CONSUMER_MAIN="org.hongxi.jaws.sample.app3.consumer.App3Consumer"
HARBORX_PROVIDER_MAIN="org.hongxi.jaws.sample.harborx.provider.HarborxProvider"
HARBORX_CONSUMER_MAIN="org.hongxi.jaws.sample.harborx.consumer.HarborxConsumer"
RAFT_PROVIDER_MAIN="org.hongxi.jaws.sample.raft.provider.RaftProvider"
RAFT_CONSUMER_MAIN="org.hongxi.jaws.sample.raft.consumer.RaftConsumer"
RAFTX_PROVIDER_MAIN="org.hongxi.jaws.sample.raftx.provider.RaftxProvider"
RAFTX_CONSUMER_MAIN="org.hongxi.jaws.sample.raftx.consumer.RaftxConsumer"
STORE_BROKER_MAIN="org.hongxi.jaws.sample.store.StoreBrokerMain"
STORE_PRODUCER_MAIN="org.hongxi.jaws.sample.store.StoreProducerMain"
STORE_CONSUMER_MAIN="org.hongxi.jaws.sample.store.StoreConsumerMain"
STORE_BENCH_MAIN="org.hongxi.jaws.sample.store.StoreBenchmark"

usage() {
    cat <<'EOF'

  Jaws RPC Samples

  Usage: ./run-sample.sh <command> [options]

  Commands:
    build              Build the project
    injvm              Run InjvmRpcDemo (injvm protocol, no ZK required)
    provider [port]    Start ZkProvider (requires ZK at 127.0.0.1:2181)
                       port defaults to 10000, set to -1 for auto-allocation starting from 10000
    provider-bg [port] Start Provider in background, PID/log distinguished by port (e.g. .provider-10000.pid)
                       port -1 triggers auto-allocation, file suffix becomes auto-{seq}
    consumer           Run ZkConsumer (provider must be started first)
    stop               Stop all background processes and clean up pid/log files
    run [port]         One-shot ZK sample: start provider -> run consumer -> stop provider
    nacos [port]       One-shot Nacos sample (requires Nacos at 127.0.0.1:8848)
    netty [port]       One-shot Netty direct-connect sample (no registry required)
    http2 [port]       One-shot HTTP/2 direct-connect sample (incl. Server Streaming, no registry required)
    wire [port]        One-shot Wire (gRPC wire format) direct-connect sample (no registry required, default port 50051)
    interop [--keepalive]  Run all wire-interop demos (GrpcCallWireDemo, WireHealthDemo, ManagedChannelDemo, WireGoAwayDemo, WireCallGrpcDemo)
                       Add --keepalive to also run WireKeepaliveDemo (~55s)
    adaptive [port]    One-shot Adaptive direct-connect sample (single port, multi-protocol, no registry required)
    harbor-standalone [port]  Start HarborServer in standalone mode (default port 19848)
    harbor-cluster       Start 3-node HarborServer cluster (ports 19848/19849/19850)
    harbor-provider [port]  Start HarborProvider in foreground (requires HarborServer, default port 20000)
    harbor [port]      One-shot Harbor sample: Provider -> Consumer (requires HarborServer running)
    app [port]         One-shot application-level discovery sample: AppProvider -> AppConsumer
                       (registerMode=instance, local metadata; requires Nacos at 127.0.0.1:8848, provider default port 20020)
    app2 [harbor_port] [port]  Same, but the registry leg is harbor (requires HarborServer, provider default port 20021)
    app3 [port]        Same, but the registry leg is zookeeper (requires ZK at 127.0.0.1:2181, provider default port 20022)
    raft               One-shot Raft persistent-instance sample: RaftProvider -> RaftConsumer (requires HarborServer running)
    raftx              Same, but the registry leg is a real nacos-client (persistent registration + subscription)
    store [port]       One-shot jaws-store demo: broker + consumer + producer (direct-connect, default port 10911)
    store-cluster [port]  One-shot multi-master jaws-store demo: harbor + 2 brokers (port/port+1) + registry-mode clients
    bench-injvm        Benchmark - injvm protocol
    bench-jaws         Benchmark - jaws protocol (default netty transport)
    bench-wire         Benchmark - wire protocol (gRPC wire format over HTTP/2)
    bench-grpc         Benchmark - grpc-java client, server=wire (interop) or server=grpc (pure gRPC reference)
    bench-store        Benchmark - jaws-store storage kernel (open-loop staged ramp, drives MessageStore directly, no RPC)

  Benchmark Options (passed via environment variables):
    THREADS            Concurrency thread count (default 4)
    WARMUP             Warm-up seconds (default 5)
    DURATION           Measurement seconds (default 10)
    PORT               Protocol port (bench-jaws default 10010, bench-wire default 50051)
    SERIALIZATION      Serialization method (default fastjson2, bench-jaws only)
    TRANSPORT          Transport layer: netty (default) or http2 (bench-jaws only)
    COMPRESSION        Compression method (default empty, bench-wire only)
    SLEEP              Simulated business processing time on provider side in ms (default 0, no simulation, bench-jaws only)
    ROLE               Run role (default all, same process; provider/consumer for separate processes)
    SERVER             Server stack for bench-grpc: wire (default, interop) or grpc (pure gRPC reference)
    HOST               Provider address (default 127.0.0.1, separate-process mode only)
    STORE_PATH         bench-store data dir (default /tmp/jaws-store-bench, wiped at start)
    MSG_SIZE           bench-store message body bytes (default 128)
    QUEUES             bench-store queue count (default 8)
    PRODUCERS          bench-store producer thread count (default 16)
    STEPS              bench-store staged target rates msg/s, comma separated, 0 = free-run (default 50000,100000,200000,400000,0)
    STEP_SECONDS       bench-store seconds per paced stage (default 30)
    RETENTION_MILLIS   bench-store commitlog file retention, keep short to bound disk (default 60000)
    CLEANUP_MILLIS     bench-store cleanup sweep interval (default 3000)

  Examples:
    ./run-sample.sh build
    ./run-sample.sh injvm
    ./run-sample.sh provider
    ./run-sample.sh provider 10001
    ./run-sample.sh provider-bg        # Start in background, port 10000
    ./run-sample.sh provider-bg 10001  # Start in background, port 10001
    ./run-sample.sh provider-bg -1     # Start in background, auto-allocate port
    ./run-sample.sh consumer
    ./run-sample.sh stop               # Stop all background processes
    ./run-sample.sh run                # One-shot ZK provider + consumer
    ./run-sample.sh nacos              # One-shot Nacos provider + consumer
    ./run-sample.sh netty              # One-shot Netty direct-connect provider + consumer
    ./run-sample.sh http2              # One-shot HTTP/2 direct-connect (with streaming) provider + consumer
    ./run-sample.sh wire               # One-shot Wire direct-connect provider + consumer
    ./run-sample.sh interop            # All wire-interop demos (quick, ~10s)
    ./run-sample.sh interop --keepalive  # All demos including keepalive (~65s)
    ./run-sample.sh adaptive           # One-shot Adaptive direct-connect (multi-protocol) provider + consumer
    ./run-sample.sh harbor-standalone      # Start HarborServer on port 19848
    ./run-sample.sh harbor-cluster          # Start 3-node cluster (19848/19849/19850)
    ./run-sample.sh harbor-provider    # Start HarborProvider port 20000 (foreground, Ctrl+C to stop)
    ./run-sample.sh harbor-provider 10001  # Start HarborProvider port 10001
    ./run-sample.sh harbor             # One-shot Provider + Consumer (requires HarborServer)
    ./run-sample.sh app                # One-shot application-level discovery demo (requires Nacos)
    ./run-sample.sh app2               # Same, but the registry leg is harbor (requires HarborServer)
    ./run-sample.sh app3               # Same, but the registry leg is zookeeper (requires ZK)
    ./run-sample.sh harborx            # Same, but the registry leg is jaws-registry-nacos
    ./run-sample.sh raft               # One-shot persistent-instance (Raft CP) demo
    ./run-sample.sh raftx              # Same, but the registry leg is a real nacos-client
    ./run-sample.sh store              # One-shot jaws-store broker + consumer + producer
    COUNT=5000 MODE=async ./run-sample.sh store   # Store demo with 5000 async sends
    ./run-sample.sh store-cluster      # Multi-master: harbor + 2 brokers + registry-mode clients
    ./run-sample.sh bench-store        # Storage kernel limit-throughput bench (direct engine drive, no RPC)
    THREADS=8 DURATION=20 ./run-sample.sh bench-jaws
    SERIALIZATION=hessian2 ./run-sample.sh bench-jaws
    TRANSPORT=http2 THREADS=20 DURATION=40 ./run-sample.sh bench-jaws
    SLEEP=5 ./run-sample.sh bench-jaws       # Simulate 5ms business latency
    THREADS=20 DURATION=40 ./run-sample.sh bench-wire
    COMPRESSION=gzip ./run-sample.sh bench-wire
    # grpc-java client benchmark: against jaws wire server (interop, needs a
    # provider from bench-wire ROLE=provider) or pure grpc-java reference server
    SERVER=wire THREADS=20 DURATION=40 ./run-sample.sh bench-grpc
    SERVER=grpc THREADS=20 DURATION=40 ./run-sample.sh bench-grpc
    # Separate-process benchmark: run in two terminals (bench-jaws only)
    ROLE=provider ./run-sample.sh bench-jaws
    ROLE=consumer THREADS=20 ./run-sample.sh bench-jaws

EOF
}

ensure_built() {
    # mvn exec:java / java -cp rely on compiled target/classes,
    # so re-install whenever any module's sources changed since last build.
    local marker=".build-timestamp"
    local need_build=0
    if [ ! -f "$marker" ]; then
        need_build=1
    else
        for src in jaws-stream-api/src/main/java jaws-core/src/main/java jaws-wire/src/main/java jaws-harbor/src/main/java jaws-store/src/main/java jaws-registry-zookeeper/src/main/java jaws-registry-nacos/src/main/java jaws-samples/*/src/main/java jaws-samples/jaws-sample-gray/*/src/main/java; do
            if [ -d "$src" ] && [ "$(find "$src" -name '*.java' -newer "$marker" -print -quit 2>/dev/null)" ]; then
                need_build=1
                break
            fi
        done
    fi
    if [ $need_build -eq 1 ]; then
        echo "Project not built or sources updated, building and installing..."
        $MVN install -DskipTests -q
        touch "$marker"
    fi
}

#
# Build full classpath for java -cp (project modules + external dependencies)
# Uses target/classes for project modules to avoid duplicate SPI files from ~/.m2 JARs.
#
build_classpath() {
    local module="$1"
    local deps
    deps=$($MVN -pl "$module" dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout -DexcludeGroupIds=org.hongxi -q 2>/dev/null)
    # Only include registry modules whose client jars are present in deps,
    # otherwise their SPI classes fail to load with NoClassDefFoundError.
    local project_cp="jaws-stream-api/target/classes:jaws-core/target/classes:jaws-samples/jaws-sample-api/target/classes"
    case "$deps" in *curator*) project_cp="$project_cp:jaws-registry-zookeeper/target/classes" ;; esac
    case "$deps" in *nacos-client*) project_cp="$project_cp:jaws-registry-nacos/target/classes" ;; esac
    case "$deps" in *protobuf-java*) project_cp="$project_cp:jaws-wire-proto/target/classes:jaws-wire/target/classes:jaws-samples/jaws-sample-wire-api/target/classes" ;; esac
    # Include jaws-harbor classes if the module depends on it
    if grep -q 'jaws-harbor' "$module/pom.xml" 2>/dev/null; then
        project_cp="$project_cp:jaws-harbor/target/classes"
    fi
    # Include jaws-store classes if the module depends on it
    if grep -q '<artifactId>jaws-store</artifactId>' "$module/pom.xml" 2>/dev/null; then
        project_cp="$project_cp:jaws-store/target/classes"
    fi
    # The native harbor registry leg needs its SPI classes and the harbor client
    # (jaws-harbor) on the path; it does not touch nacos-client at all.
    if grep -q 'jaws-registry-harbor' "$module/pom.xml" 2>/dev/null; then
        project_cp="$project_cp:jaws-registry-harbor/target/classes:jaws-harbor/target/classes"
    fi
    echo "$project_cp:$deps"
}

cmd_build() {
    echo "Building project..."
    $MVN clean install -DskipTests -q
    touch .build-timestamp
    echo "Build complete."
}

cmd_injvm() {
    ensure_built
    echo "Running InjvmRpcDemo..."
    echo "--------------------------------------------"
    $MVN exec:java -pl "$INJVM_MODULE" -Dexec.mainClass="$INJVM_MAIN" -q
}

cmd_provider() {
    ensure_built
    local port="${1:-10000}"
    echo "Starting ZkProvider (jaws + ZooKeeper) port=$port"
    echo "Make sure ZooKeeper is running at 127.0.0.1:2181"
    echo "--------------------------------------------"
    $MVN exec:java -pl "$PROVIDER_MODULE" \
        -Dexec.mainClass="$PROVIDER_MAIN" \
        -Dport="$port" \
        -q
}

cmd_provider_bg() {
    ensure_built
    local port="${1:-10000}"
    local cp
    cp=$(build_classpath "$PROVIDER_MODULE")

    # Determine file suffix: fixed port uses port number, -1 uses auto-{seq}
    local suffix
    if [ "$port" = "-1" ]; then
        local seq=1
        while [ -f ".provider-auto-${seq}.pid" ]; do
            seq=$((seq + 1))
        done
        suffix="auto-${seq}"
    else
        suffix="$port"
    fi
    local pid_file=".provider-${suffix}.pid"
    local log_file="provider-${suffix}.log"

    echo "Starting ZkProvider in background port=$port ..."
    java -cp "$cp:$PROVIDER_MODULE/target/classes" \
        -Dport="$port" \
        "$PROVIDER_MAIN" > "$log_file" 2>&1 &
    local pid=$!
    echo "$pid" > "$pid_file"
    echo "Provider started (PID=$pid), log: $log_file"
    echo "Stop: kill \$(cat $pid_file)"
}

cmd_stop() {
    local count=0

    # 1. Kill processes by pid files
    for pid_file in *.pid .*.pid; do
        [ -f "$pid_file" ] || continue
        local pid
        pid=$(cat "$pid_file")
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null
            echo "Stopped process $pid (from $pid_file)"
            count=$((count + 1))
        else
            echo "Process $pid already dead (from $pid_file)"
        fi
        rm -f "$pid_file"
    done

    # 2. Kill any remaining Jaws sample Java processes
    for main_class in "$PROVIDER_MAIN" "$BENCHMARK_MAIN" "$HARBOR_BOOTSTRAP_MAIN" "$STORE_BROKER_MAIN"; do
        local pids
        pids=$(jps -l 2>/dev/null | grep "$main_class" | awk '{print $1}')
        for pid in $pids; do
            kill "$pid" 2>/dev/null
            echo "Stopped process $pid ($main_class)"
            count=$((count + 1))
        done
    done

    # 3. Clean up log files
    for log_file in provider-*.log harbor-node-*.log; do
        if [ -f "$log_file" ]; then
            rm -f "$log_file"
            echo "Removed $log_file"
        fi
    done

    if [ $count -eq 0 ]; then
        echo "No running processes found."
    else
        echo "Stopped $count process(es)."
    fi
}

#
# PID currently holding a TCP listen port; empty when nothing listens.
#
port_holder_pid() {
    lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1
}

#
# Whether something accepts connections on 127.0.0.1:<port>.
#
port_open() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

#
# Stop a provider started by this script and drop its pid file. Idempotent, so
# it is safe for both the normal path and a signal trap firing twice.
#
cleanup_provider() {
    local pid="${1:-}" pid_file="${2:-}"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    [ -n "$pid_file" ] && rm -f "$pid_file"
    return 0
}

#
# Generic one-shot run: start provider -> run consumer -> stop provider.
# $1 name  $2 provider module  $3 provider main  $4 consumer module  $5 consumer main
# $6 default port  $7 prerequisite hint  $8 port
#
run_pair() {
    local name="$1" provider_module="$2" provider_main="$3" consumer_module="$4" consumer_main="$5"
    local default_port="$6" hint="$7" port="${8:-}"
    [ -z "$port" ] && port="$default_port"

    ensure_built
    local cp
    cp=$(build_classpath "$provider_module")
    local pid_file=".provider-${name}.pid"
    local log_file="provider-${name}.log"

    echo "=== One-shot run ($name) ==="
    [ -n "$hint" ] && echo "$hint"
    echo ""

    # 0. Refuse to start on a port someone else already holds. The readiness
    #    probe cannot tell this run's provider from a leftover one, so without
    #    this check a stale process makes the probe print "ready (0s)", our own
    #    provider dies on bind, and the consumer reports "no available
    #    references" with nothing pointing at the real cause.
    local holder holder_main
    holder=$(port_holder_pid "$port")
    if [ -n "$holder" ]; then
        holder_main=$(jps -l 2>/dev/null | awk -v p="$holder" '$1 == p {print $2}')
        echo "Port $port is already held by PID $holder${holder_main:+ ($holder_main)}." >&2
        echo "That is a provider left behind by an earlier run, not the one this run starts." >&2
        # The exact kill is listed first: when the provider was started through
        # the Maven wrapper, "jps | grep <main class>" inside cmd_stop does not
        # see it, so the sweep is not always enough.
        echo "Stop it first:  kill $holder     (or: ./run-sample.sh stop)" >&2
        exit 1
    fi
    # A pid file with no live process behind it is residue from a killed run.
    rm -f "$pid_file"

    # 1. Start provider in background
    echo "[1/4] Starting Provider port=$port ..."
    java -cp "$cp:$provider_module/target/classes" \
        -Dport="$port" \
        "$provider_main" > "$log_file" 2>&1 &
    local pid=$!
    echo "$pid" > "$pid_file"

    # Reap the provider on every exit path. A failing consumer or a Ctrl-C used
    # to leave it running forever, which then poisoned the next run through the
    # check above.
    trap "cleanup_provider $pid '$pid_file'" EXIT
    trap "cleanup_provider $pid '$pid_file'; exit 130" INT TERM

    # 2. Wait for the provider we started to accept connections on the port
    printf '[2/4] Waiting for Provider ready '
    local max_wait=15
    local waited=0
    local ready=0
    while [ $waited -lt $max_wait ]; do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo ""
            echo "Provider died before it was ready. Tail of $log_file:" >&2
            tail -5 "$log_file" >&2 || true
            exit 1
        fi
        if port_open "$port"; then
            ready=1
            echo " ready (${waited}s)"
            break
        fi
        sleep 1
        waited=$((waited + 1))
        printf '.'
    done
    if [ $ready -eq 0 ]; then
        echo ""
        echo "Provider not ready after ${max_wait}s; continuing anyway, the consumer may fail." >&2
    fi

    # 3. Run consumer
    echo "[3/4] Running Consumer ..."
    echo "--------------------------------------------"
    local consumer_cp
    local consumer_exit=0
    consumer_cp=$(build_classpath "$consumer_module")
    # Not "java ...; rc=$?": under set -e a failing consumer would end the script
    # on that line, before the provider below it could ever be stopped.
    if java -cp "$consumer_cp:$consumer_module/target/classes" \
        -DdirectUrl="127.0.0.1:$port" \
        "$consumer_main"; then
        consumer_exit=0
    else
        consumer_exit=$?
    fi
    echo "--------------------------------------------"

    # 4. Stop provider and clean up
    echo "[4/4] Stopping Provider ..."
    cleanup_provider "$pid" "$pid_file"
    trap - EXIT INT TERM

    if [ $consumer_exit -ne 0 ]; then
        # Keep the log when the run failed: that is exactly when it is needed,
        # and deleting it left nothing to diagnose afterwards.
        echo "Consumer exit code: $consumer_exit"
        echo "Provider log kept for inspection: $log_file"
        exit $consumer_exit
    fi
    rm -f "$log_file"
    echo "=== Done ==="
}

cmd_run() {
    run_pair "zk" "$PROVIDER_MODULE" "$PROVIDER_MAIN" "$CONSUMER_MODULE" "$CONSUMER_MAIN" \
        10000 "Make sure ZooKeeper is running at 127.0.0.1:2181" "${1:-}"
}

cmd_run_nacos() {
    run_pair "nacos" "$NACOS_PROVIDER_MODULE" "$NACOS_PROVIDER_MAIN" "$NACOS_CONSUMER_MODULE" "$NACOS_CONSUMER_MAIN" \
        10000 "Make sure Nacos is running at 127.0.0.1:8848" "${1:-}"
}

cmd_run_netty() {
    run_pair "netty" "$NETTY_PROVIDER_MODULE" "$NETTY_PROVIDER_MAIN" "$NETTY_CONSUMER_MODULE" "$NETTY_CONSUMER_MAIN" \
        10000 "" "${1:-}"
}

cmd_run_http2() {
    run_pair "http2" "$HTTP2_PROVIDER_MODULE" "$HTTP2_PROVIDER_MAIN" "$HTTP2_CONSUMER_MODULE" "$HTTP2_CONSUMER_MAIN" \
        10000 "" "${1:-}"
}

cmd_run_wire() {
    run_pair "wire" "$WIRE_PROVIDER_MODULE" "$WIRE_PROVIDER_MAIN" "$WIRE_CONSUMER_MODULE" "$WIRE_CONSUMER_MAIN" \
        50051 "" "${1:-}"
}

cmd_run_adaptive() {
    run_pair "adaptive" "$ADAPTIVE_PROVIDER_MODULE" "$ADAPTIVE_PROVIDER_MAIN" "$ADAPTIVE_CONSUMER_MODULE" "$ADAPTIVE_CONSUMER_MAIN" \
        10000 "" "${1:-}"
}

#
# One-shot store demo: broker in background -> consumer (background) ->
# producer. The consumer starts first so its pulls suspend and long-poll on
# the empty queue; the producer then wakes them, exercising the hold/wakeup
# path (and the provider-side requestTimeout that must outlive the hold).
# Two client legs against one storage broker, so the run_pair flow is
# spelled out here instead of reused.
# Env: COUNT (messages, default 1000), MODE (sync|async|oneway), SYNC_FLUSH.
#
cmd_store() {
    local port="${1:-10911}"
    local count="${COUNT:-1000}"
    local mode="${MODE:-sync}"

    ensure_built
    local cp
    cp=$(build_classpath "$STORE_MODULE")
    local pid_file=".provider-store.pid"
    local log_file="provider-store.log"

    echo "=== One-shot run (store) ==="
    echo "count=$count mode=$mode port=$port"
    echo ""

    # Fresh data dirs per run: the demo must be reproducible no matter what
    # an earlier run left behind (stale client offsets against a wiped store
    # would otherwise hang the consumer until its timeout).
    local run_dir
    run_dir=$(mktemp -d "${TMPDIR:-/tmp}/jaws-store-demo.XXXXXX")

    # 0. Refuse to start on a port someone else already holds (see run_pair).
    local holder holder_main
    holder=$(port_holder_pid "$port")
    if [ -n "$holder" ]; then
        holder_main=$(jps -l 2>/dev/null | awk -v p="$holder" '$1 == p {print $2}')
        echo "Port $port is already held by PID $holder${holder_main:+ ($holder_main)}." >&2
        echo "Stop it first:  kill $holder     (or: ./run-sample.sh stop)" >&2
        rm -rf "$run_dir"
        exit 1
    fi
    rm -f "$pid_file"

    # 1. Start the store broker in background
    echo "[1/5] Starting StoreBroker port=$port ..."
    java -cp "$cp:$STORE_MODULE/target/classes" \
        -Dport="$port" \
        -DstorePath="$run_dir/data" \
        -DsyncFlush="${SYNC_FLUSH:-false}" \
        "$STORE_BROKER_MAIN" > "$log_file" 2>&1 &
    local pid=$!
    echo "$pid" > "$pid_file"
    trap "cleanup_provider $pid '$pid_file'; rm -rf '$run_dir'" EXIT
    trap "cleanup_provider $pid '$pid_file'; rm -rf '$run_dir'; exit 130" INT TERM

    # 2. Wait for the broker to accept connections
    printf '[2/5] Waiting for StoreBroker ready '
    local max_wait=15
    local waited=0
    local ready=0
    while [ $waited -lt $max_wait ]; do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo ""
            echo "StoreBroker died before it was ready. Tail of $log_file:" >&2
            tail -5 "$log_file" >&2 || true
            exit 1
        fi
        if port_open "$port"; then
            ready=1
            echo " ready (${waited}s)"
            break
        fi
        sleep 1
        waited=$((waited + 1))
        printf '.'
    done
    if [ $ready -eq 0 ]; then
        echo ""
        echo "StoreBroker not ready after ${max_wait}s; continuing anyway, the clients may fail." >&2
    fi

    # 3. Consume first (in the background) so its pulls suspend and long-poll
    #    on the empty queue; the producer below then wakes them. This ordering
    #    is what actually exercises the hold/wakeup path. A brief settle keeps
    #    the consumer's first pull suspended when the sends arrive.
    echo "[3/5] Consuming in background, expecting $count messages (long-poll hold) ..."
    echo "--------------------------------------------"
    local consumer_log="consumer-store.log"
    local consumer_pid_file=".consumer-store.pid"
    java -cp "$cp:$STORE_MODULE/target/classes" \
        -DdirectUrl="127.0.0.1:$port" -Dexpected="$count" \
        "$STORE_CONSUMER_MAIN" > "$consumer_log" 2>&1 &
    local consumer_pid=$!
    echo "$consumer_pid" > "$consumer_pid_file"
    # Grow the cleanup chain so a Ctrl-C in the window below also reaps the
    # background consumer, not just the broker.
    trap "cleanup_provider $consumer_pid '$consumer_pid_file'; cleanup_provider $pid '$pid_file'; rm -rf '$run_dir'" EXIT
    trap "cleanup_provider $consumer_pid '$consumer_pid_file'; cleanup_provider $pid '$pid_file'; rm -rf '$run_dir'; exit 130" INT TERM
    sleep 2

    # 4. Produce (guarded like run_pair: under set -e a failing leg must not
    #    end the script before the broker and consumer can be stopped). The
    #    sends wake the consumer's suspended pulls.
    echo "[4/5] Sending $count messages ($mode) to wake suspended pulls ..."
    local producer_exit=0
    if java -cp "$cp:$STORE_MODULE/target/classes" \
        -DdirectUrl="127.0.0.1:$port" -Dcount="$count" -Dmode="$mode" \
        "$STORE_PRODUCER_MAIN"; then
        producer_exit=0
    else
        producer_exit=$?
    fi

    # Drain the consumer: if the producer failed, the consumer would otherwise
    # block until its own timeout, so reap it early instead of waiting.
    local consumer_exit=0
    if [ $producer_exit -ne 0 ]; then
        consumer_exit=$producer_exit
        kill "$consumer_pid" 2>/dev/null || true
        wait "$consumer_pid" 2>/dev/null || true
        echo "Skipped: producer failed with exit code $producer_exit" >&2
    elif wait "$consumer_pid"; then
        consumer_exit=0
    else
        consumer_exit=$?
    fi
    cat "$consumer_log"
    echo "--------------------------------------------"

    # 5. Stop broker and clean up
    echo "[5/5] Stopping StoreBroker ..."
    cleanup_provider "$pid" "$pid_file"
    trap - EXIT INT TERM

    local exit_code=$consumer_exit
    [ $producer_exit -ne 0 ] && exit_code=$producer_exit
    if [ $exit_code -ne 0 ]; then
        echo "Exit code: $exit_code"
        echo "Broker log kept for inspection: $log_file"
        echo "Consumer log kept for inspection: $consumer_log"
        rm -rf "$run_dir"
        exit $exit_code
    fi
    rm -rf "$run_dir"
    rm -f "$log_file" "$consumer_log" "$consumer_pid_file"
    echo "=== Done ==="
}

#
# One-shot multi-master store demo: harbor registry + two brokers (distinct
# brokerName and data dirs) -> registry-mode consumer (background, one pump
# per broker, offsets keyed by brokerName) -> registry-mode producer
# (load-balanced puts waking the suspended pulls, see cmd_store).
# No rebalance by design: the single consumer instance drains both brokers.
# Env: COUNT (messages, default 1000), MODE (sync|async|oneway), SYNC_FLUSH,
#      HARBOR_PORT (registry port, default 19848).
#
cmd_store_cluster() {
    local port="${1:-10911}"
    local port_b=$((port + 1))
    local harbor_port="${HARBOR_PORT:-19848}"
    local count="${COUNT:-1000}"
    local mode="${MODE:-sync}"

    ensure_built
    local cp bootstrap_cp
    cp=$(build_classpath "$STORE_MODULE")
    bootstrap_cp=$(build_classpath "$HARBOR_BOOTSTRAP_MODULE")

    echo "=== One-shot run (store-cluster) ==="
    echo "count=$count mode=$mode brokers=$port/$port_b registry=127.0.0.1:$harbor_port"
    echo ""

    # Fresh data dirs per run (see cmd_store): stale offsets against a wiped
    # store would hang the consumer until its timeout.
    local run_dir
    run_dir=$(mktemp -d "${TMPDIR:-/tmp}/jaws-store-cluster.XXXXXX")

    # Refuse to start on ports someone else already holds (see run_pair).
    local p holder holder_main
    for p in "$harbor_port" "$port" "$port_b"; do
        holder=$(port_holder_pid "$p")
        if [ -n "$holder" ]; then
            holder_main=$(jps -l 2>/dev/null | awk -v pid="$holder" '$1 == pid {print $2}')
            echo "Port $p is already held by PID $holder${holder_main:+ ($holder_main)}." >&2
            echo "Stop it first:  kill $holder     (or: ./run-sample.sh stop)" >&2
            rm -rf "$run_dir"
            exit 1
        fi
    done

    # Cleanup chain grows as each process starts; pids are expanded into the
    # trap string right away so it stays valid after this function unwinds.
    local cleanup="rm -rf '$run_dir'"
    trap "$cleanup" EXIT
    trap "$cleanup; exit 130" INT TERM

    # 1. Start the harbor registry in background
    echo "[1/6] Starting HarborServer (standalone) port=$harbor_port ..."
    java -cp "$bootstrap_cp:$HARBOR_BOOTSTRAP_MODULE/target/classes" \
        "$HARBOR_BOOTSTRAP_MAIN" "$harbor_port" > "provider-store-cluster-harbor.log" 2>&1 &
    local harbor_pid=$!
    echo "$harbor_pid" > ".provider-store-cluster-harbor.pid"
    cleanup="cleanup_provider $harbor_pid '.provider-store-cluster-harbor.pid'; $cleanup"
    trap "$cleanup" EXIT
    trap "$cleanup; exit 130" INT TERM

    local max_wait=20
    local waited=0
    while [ $waited -lt $max_wait ] && ! port_open "$harbor_port"; do
        if ! kill -0 "$harbor_pid" 2>/dev/null; then
            echo "HarborServer died before it was ready. Tail of provider-store-cluster-harbor.log:" >&2
            tail -5 "provider-store-cluster-harbor.log" >&2 || true
            exit 1
        fi
        sleep 1
        waited=$((waited + 1))
    done
    if ! port_open "$harbor_port"; then
        echo "HarborServer not ready after ${max_wait}s; continuing anyway, the clients may fail." >&2
    fi

    # 2. Start two brokers with distinct identities and data dirs
    local name broker_port broker_pid
    for name in a b; do
        broker_port=$port
        [ "$name" = "b" ] && broker_port=$port_b
        echo "[2/6] Starting StoreBroker broker-$name port=$broker_port ..."
        java -cp "$cp:$STORE_MODULE/target/classes" \
            -Dport="$broker_port" \
            -DbrokerName="broker-$name" \
            -DstorePath="$run_dir/data-$name" \
            -DsyncFlush="${SYNC_FLUSH:-false}" \
            -Dregistry="harbor://127.0.0.1:$harbor_port" \
            "$STORE_BROKER_MAIN" > "provider-store-cluster-$name.log" 2>&1 &
        broker_pid=$!
        echo "$broker_pid" > ".provider-store-cluster-$name.pid"
        cleanup="cleanup_provider $broker_pid '.provider-store-cluster-$name.pid'; $cleanup"
        trap "$cleanup" EXIT
        trap "$cleanup; exit 130" INT TERM
    done

    # 3. Wait for both brokers to accept connections
    printf '[3/6] Waiting for StoreBrokers ready '
    waited=0
    local ready=0
    while [ $waited -lt 15 ]; do
        if port_open "$port" && port_open "$port_b"; then
            ready=1
            echo " ready (${waited}s)"
            break
        fi
        for name in a b; do
            broker_pid=$(cat ".provider-store-cluster-$name.pid")
            if ! kill -0 "$broker_pid" 2>/dev/null; then
                echo ""
                echo "StoreBroker broker-$name died before it was ready. Tail of provider-store-cluster-$name.log:" >&2
                tail -5 "provider-store-cluster-$name.log" >&2 || true
                exit 1
            fi
        done
        sleep 1
        waited=$((waited + 1))
        printf '.'
    done
    if [ $ready -eq 0 ]; then
        echo ""
        echo "StoreBrokers not ready after 15s; continuing anyway, the clients may fail." >&2
    fi

    # 4. Consume first (in the background) so the per-broker pumps suspend and
    #    long-poll on the empty queues; the producer below then wakes them
    #    (same ordering rationale as cmd_store). A brief settle keeps the
    #    first pulls suspended when the sends arrive.
    echo "[4/6] Consuming in background, expecting $count messages from both brokers (long-poll hold) ..."
    echo "--------------------------------------------"
    local consumer_log="consumer-store-cluster.log"
    local consumer_pid_file=".consumer-store-cluster.pid"
    java -cp "$cp:$STORE_MODULE/target/classes" \
        -Dregistry="harbor://127.0.0.1:$harbor_port" -Dexpected="$count" \
        "$STORE_CONSUMER_MAIN" > "$consumer_log" 2>&1 &
    local consumer_pid=$!
    echo "$consumer_pid" > "$consumer_pid_file"
    cleanup="cleanup_provider $consumer_pid '$consumer_pid_file'; $cleanup"
    trap "$cleanup" EXIT
    trap "$cleanup; exit 130" INT TERM
    sleep 2

    # 5. Produce through the registry (puts spread across both brokers); the
    #    sends wake the consumer's suspended pulls.
    echo "[5/6] Sending $count messages ($mode, registry mode) to wake suspended pulls ..."
    local producer_exit=0
    if java -cp "$cp:$STORE_MODULE/target/classes" \
        -Dregistry="harbor://127.0.0.1:$harbor_port" -Dcount="$count" -Dmode="$mode" \
        "$STORE_PRODUCER_MAIN"; then
        producer_exit=0
    else
        producer_exit=$?
    fi

    # Drain the consumer: if the producer failed, the consumer would otherwise
    # block until its own timeout, so reap it early instead of waiting.
    local consumer_exit=0
    if [ $producer_exit -ne 0 ]; then
        consumer_exit=$producer_exit
        kill "$consumer_pid" 2>/dev/null || true
        wait "$consumer_pid" 2>/dev/null || true
        echo "Skipped: producer failed with exit code $producer_exit" >&2
    elif wait "$consumer_pid"; then
        consumer_exit=0
    else
        consumer_exit=$?
    fi
    cat "$consumer_log"
    echo "--------------------------------------------"

    # 6. Stop everything and clean up
    echo "[6/6] Stopping StoreBrokers and HarborServer ..."
    eval "$cleanup"
    trap - EXIT INT TERM

    local exit_code=$consumer_exit
    [ $producer_exit -ne 0 ] && exit_code=$producer_exit
    if [ $exit_code -ne 0 ]; then
        echo "Exit code: $exit_code"
        echo "Logs kept for inspection: provider-store-cluster-*.log, $consumer_log"
        exit $exit_code
    fi
    rm -f provider-store-cluster-*.log "$consumer_log"
    echo "=== Done ==="
}

cmd_harbor_standalone() {
    ensure_built
    local harbor_port="${1:-19848}"

    local bootstrap_cp
    bootstrap_cp=$(build_classpath "$HARBOR_BOOTSTRAP_MODULE")

    echo "Starting HarborServer (standalone) on port $harbor_port ..."
    echo "Single-node mode (no cluster peers)"
    echo "HTTP Management API: http://localhost:$((harbor_port + 10))/api/*"
    echo "Press Ctrl+C to stop."
    echo "--------------------------------------------"
    java -cp "$bootstrap_cp:$HARBOR_BOOTSTRAP_MODULE/target/classes" \
        "$HARBOR_BOOTSTRAP_MAIN" "$harbor_port"
}

cmd_harbor_cluster() {
    ensure_built
    local cluster="127.0.0.1:19848,127.0.0.1:19849,127.0.0.1:19850"
    local ports=(19848 19849 19850)

    local bootstrap_cp
    bootstrap_cp=$(build_classpath "$HARBOR_BOOTSTRAP_MODULE")

    echo "Starting 3-node HarborServer cluster ..."
    echo "Cluster members: $cluster"
    echo "--------------------------------------------"

    local pids=()
    for port in "${ports[@]}"; do
        java -cp "$bootstrap_cp:$HARBOR_BOOTSTRAP_MODULE/target/classes" \
            "$HARBOR_BOOTSTRAP_MAIN" "$port" --cluster "$cluster" \
            > "harbor-node-${port}.log" 2>&1 &
        pids+=($!)
        echo "$!" > ".harbor-${port}.pid"
        echo "  Node started: port=$port  PID=$!  log=harbor-node-${port}.log"
    done

    echo ""
    echo -n "Waiting for all nodes ready "
    local max_wait=20
    local waited=0
    while [ $waited -lt $max_wait ]; do
        local all_ready=1
        for port in "${ports[@]}"; do
            if ! (echo >/dev/tcp/127.0.0.1/$port) 2>/dev/null; then
                all_ready=0
                break
            fi
        done
        if [ $all_ready -eq 1 ]; then
            echo " ready (${waited}s)"
            break
        fi
        sleep 1
        waited=$((waited + 1))
        echo -n "."
    done
    if [ $waited -ge $max_wait ]; then
        echo " timeout (${max_wait}s)"
    fi

    echo ""
    echo "Management APIs:"
    for port in "${ports[@]}"; do
        echo "  http://localhost:$((port + 10))/api/*"
    done
    echo ""
    echo "Run ./run-sample.sh stop to shut down all nodes."
    echo "============================================"

    # Wait for any child to exit
    wait "${pids[0]}" 2>/dev/null || true
}

# Point the shared harbor one-shot at the nacos-leg sample modules, so the two
# legs are exercised by the same script rather than by a copy of it.
_use_harborx_leg() {
    HARBOR_PROVIDER_MODULE="$HARBORX_PROVIDER_MODULE"
    HARBOR_CONSUMER_MODULE="$HARBORX_CONSUMER_MODULE"
    HARBOR_PROVIDER_MAIN="$HARBORX_PROVIDER_MAIN"
    HARBOR_CONSUMER_MAIN="$HARBORX_CONSUMER_MAIN"
}

cmd_harborx() {
    _use_harborx_leg
    cmd_harbor "$@"
}

cmd_harborx_provider() {
    _use_harborx_leg
    cmd_harbor_provider "$@"
}

cmd_harbor_provider() {
    ensure_built
    local provider_port="${1:-20000}"
    local harbor_port=19848

    # Check if HarborServer is already running
    local harbor_running=0
    if command -v lsof >/dev/null 2>&1; then
        if lsof -Pi :$harbor_port -sTCP:LISTEN -t >/dev/null 2>&1; then
            harbor_running=1
        fi
    else
        if (echo > /dev/tcp/127.0.0.1/$harbor_port) 2>/dev/null; then
            harbor_running=1
        fi
    fi

    if [ $harbor_running -eq 0 ]; then
        echo "HarborServer is not running on port $harbor_port."
        echo ""
        echo "Please start it first:"
        echo "  ./run-sample.sh harbor-standalone"
        exit 1
    fi
    echo "HarborServer detected on port $harbor_port."
    echo "Starting HarborProvider port=$provider_port (Ctrl+C to stop) ..."
    echo "--------------------------------------------"
    local provider_cp
    provider_cp=$(build_classpath "$HARBOR_PROVIDER_MODULE")
    java -cp "$provider_cp:$HARBOR_PROVIDER_MODULE/target/classes" \
        -Dport="$provider_port" \
        -Dharbor.port="$harbor_port" \
        "$HARBOR_PROVIDER_MAIN"
}

cmd_harbor() {
    ensure_built
    local harbor_port="${1:-19848}"
    local provider_port="${2:-20000}"

    # Check if HarborServer is already running (use lsof to avoid triggering HTTP/2 handler errors)
    local harbor_running=0
    if command -v lsof >/dev/null 2>&1; then
        if lsof -Pi :$harbor_port -sTCP:LISTEN -t >/dev/null 2>&1; then
            harbor_running=1
        fi
    else
        # Fallback: try /dev/tcp but suppress errors
        if (echo > /dev/tcp/127.0.0.1/$harbor_port) 2>/dev/null; then
            harbor_running=1
        fi
    fi

    if [ $harbor_running -eq 0 ]; then
        echo "HarborServer is not running on port $harbor_port."
        echo ""
        echo "Please start it first:"
        echo "  ./run-sample.sh harbor-standalone          # port 19848"
        echo "  ./run-sample.sh harbor-standalone $harbor_port  # custom port"
        exit 1
    fi
    echo "HarborServer detected on port $harbor_port."

    local provider_pid_file=".provider-harbor-svc.pid"
    local provider_log_file="provider-harbor-svc.log"

    # 1. Start HarborProvider in background
    echo "[1/4] Starting HarborProvider port=$provider_port ..."
    local provider_cp
    provider_cp=$(build_classpath "$HARBOR_PROVIDER_MODULE")
    java -cp "$provider_cp:$HARBOR_PROVIDER_MODULE/target/classes" \
        -Dport="$provider_port" \
        -Dharbor.port="$harbor_port" \
        "$HARBOR_PROVIDER_MAIN" > "$provider_log_file" 2>&1 &
    local provider_pid=$!
    echo "$provider_pid" > "$provider_pid_file"

    # 2. Wait for provider to register services
    echo -n "[2/4] Waiting for Provider registered "
    local max_wait=15
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if grep -q "exported" "$provider_log_file" 2>/dev/null; then
            echo " ready (${waited}s)"
            break
        fi
        sleep 1
        waited=$((waited + 1))
        echo -n "."
    done
    if [ $waited -ge $max_wait ]; then
        echo " timeout (${max_wait}s), continuing..."
    fi

    # 3. Run consumer
    echo "[3/4] Running Consumer ..."
    echo "--------------------------------------------"
    local consumer_cp
    consumer_cp=$(build_classpath "$HARBOR_CONSUMER_MODULE")
    java -cp "$consumer_cp:$HARBOR_CONSUMER_MODULE/target/classes" \
        -Dharbor.port="$harbor_port" \
        "$HARBOR_CONSUMER_MAIN"
    local consumer_exit=$?
    echo "--------------------------------------------"

    # 4. Stop provider and clean up
    echo "[4/4] Stopping Provider ..."
    if kill -0 "$provider_pid" 2>/dev/null; then
        kill "$provider_pid" 2>/dev/null
        wait "$provider_pid" 2>/dev/null || true
    fi
    rm -f "$provider_pid_file" "$provider_log_file"

    if [ $consumer_exit -ne 0 ]; then
        echo "Consumer exit code: $consumer_exit"
        exit $consumer_exit
    fi
    echo "=== Done ==="
}

# One-shot application-level discovery demo: AppProvider exports DemoService +
# OrderService with registerMode=instance (one application instance + in-memory
# metadata + config mappings), AppConsumer references them plainly and is routed
# to app-level discovery automatically. See doc/app-level-discovery.md.
cmd_app() {
    ensure_built
    local provider_port="${1:-20020}"

    echo "Make sure Nacos is running at 127.0.0.1:8848."

    local provider_pid_file=".provider-app-svc.pid"
    local provider_log_file="provider-app-svc.log"

    # 1. Start AppProvider in background
    echo "[1/4] Starting AppProvider port=$provider_port (registerMode=instance) ..."
    local provider_cp
    provider_cp=$(build_classpath "$APP_PROVIDER_MODULE")
    java -cp "$provider_cp:$APP_PROVIDER_MODULE/target/classes" \
        -Dport="$provider_port" \
        "$APP_PROVIDER_MAIN" > "$provider_log_file" 2>&1 &
    local provider_pid=$!
    echo "$provider_pid" > "$provider_pid_file"

    # 2. Wait until BOTH services are exported: the second export also writes
    #    the OrderService mapping the consumer needs for app-level routing.
    echo -n "[2/4] Waiting for application instance registered "
    local max_wait=15
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if grep -q "OrderService exported" "$provider_log_file" 2>/dev/null; then
            echo " ready (${waited}s)"
            break
        fi
        sleep 1
        waited=$((waited + 1))
        echo -n "."
    done
    if [ $waited -ge $max_wait ]; then
        echo " timeout (${max_wait}s), continuing..."
    fi

    # 3. Run consumer
    echo "[3/4] Running AppConsumer ..."
    echo "--------------------------------------------"
    local consumer_cp
    consumer_cp=$(build_classpath "$APP_CONSUMER_MODULE")
    java -cp "$consumer_cp:$APP_CONSUMER_MODULE/target/classes" \
        "$APP_CONSUMER_MAIN"
    local consumer_exit=$?
    echo "--------------------------------------------"

    # 4. Stop provider and clean up
    echo "[4/4] Stopping AppProvider ..."
    if kill -0 "$provider_pid" 2>/dev/null; then
        kill "$provider_pid" 2>/dev/null
        wait "$provider_pid" 2>/dev/null || true
    fi
    rm -f "$provider_pid_file" "$provider_log_file"

    if [ $consumer_exit -ne 0 ]; then
        echo "Consumer exit code: $consumer_exit"
        exit $consumer_exit
    fi
    echo "=== Done ==="
}

# One-shot app-level discovery demo on the harbor leg: same local-metadata flow
# as cmd_app, but the instance pointer and mappings live in HarborServer.
# See doc/app-level-discovery.md.
cmd_app2() {
    ensure_built
    local harbor_port="${1:-19848}"
    local provider_port="${2:-20021}"

    # Check if HarborServer is already running (use lsof to avoid triggering HTTP/2 handler errors)
    local harbor_running=0
    if command -v lsof >/dev/null 2>&1; then
        if lsof -Pi :$harbor_port -sTCP:LISTEN -t >/dev/null 2>&1; then
            harbor_running=1
        fi
    else
        # Fallback: try /dev/tcp but suppress errors
        if (echo > /dev/tcp/127.0.0.1/$harbor_port) 2>/dev/null; then
            harbor_running=1
        fi
    fi

    if [ $harbor_running -eq 0 ]; then
        echo "HarborServer is not running on port $harbor_port."
        echo ""
        echo "Please start it first:"
        echo "  ./run-sample.sh harbor-standalone          # port 19848"
        echo "  ./run-sample.sh harbor-standalone $harbor_port  # custom port"
        exit 1
    fi
    echo "HarborServer detected on port $harbor_port."

    local provider_pid_file=".provider-app2-svc.pid"
    local provider_log_file="provider-app2-svc.log"

    # 1. Start App2Provider in background
    echo "[1/4] Starting App2Provider port=$provider_port (registerMode=instance) ..."
    local provider_cp
    provider_cp=$(build_classpath "$APP2_PROVIDER_MODULE")
    java -cp "$provider_cp:$APP2_PROVIDER_MODULE/target/classes" \
        -Dport="$provider_port" \
        -Dharbor.port="$harbor_port" \
        "$APP2_PROVIDER_MAIN" > "$provider_log_file" 2>&1 &
    local provider_pid=$!
    echo "$provider_pid" > "$provider_pid_file"

    # 2. Wait until BOTH services are exported: the second export also writes
    #    the OrderService mapping the consumer needs for app-level routing.
    echo -n "[2/4] Waiting for application instance registered "
    local max_wait=15
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if grep -q "OrderService exported" "$provider_log_file" 2>/dev/null; then
            echo " ready (${waited}s)"
            break
        fi
        sleep 1
        waited=$((waited + 1))
        echo -n "."
    done
    if [ $waited -ge $max_wait ]; then
        echo " timeout (${max_wait}s), continuing..."
    fi

    # 3. Run consumer
    echo "[3/4] Running App2Consumer ..."
    echo "--------------------------------------------"
    local consumer_cp
    consumer_cp=$(build_classpath "$APP2_CONSUMER_MODULE")
    java -cp "$consumer_cp:$APP2_CONSUMER_MODULE/target/classes" \
        -Dharbor.port="$harbor_port" \
        "$APP2_CONSUMER_MAIN"
    local consumer_exit=$?
    echo "--------------------------------------------"

    # 4. Stop provider and clean up
    echo "[4/4] Stopping App2Provider ..."
    if kill -0 "$provider_pid" 2>/dev/null; then
        kill "$provider_pid" 2>/dev/null
        wait "$provider_pid" 2>/dev/null || true
    fi
    rm -f "$provider_pid_file" "$provider_log_file"

    if [ $consumer_exit -ne 0 ]; then
        echo "Consumer exit code: $consumer_exit"
        exit $consumer_exit
    fi
    echo "=== Done ==="
}

# One-shot app-level discovery demo on the zookeeper leg: same local-metadata
# flow as cmd_app, but the instance is an ephemeral znode and the mappings are
# persistent znodes under /jaws/dynamic-config/. See doc/app-level-discovery.md.
cmd_app3() {
    ensure_built
    local provider_port="${1:-20022}"

    echo "Make sure ZooKeeper is running at 127.0.0.1:2181."

    local provider_pid_file=".provider-app3-svc.pid"
    local provider_log_file="provider-app3-svc.log"

    # 1. Start App3Provider in background
    echo "[1/4] Starting App3Provider port=$provider_port (registerMode=instance) ..."
    local provider_cp
    provider_cp=$(build_classpath "$APP3_PROVIDER_MODULE")
    java -cp "$provider_cp:$APP3_PROVIDER_MODULE/target/classes" \
        -Dport="$provider_port" \
        "$APP3_PROVIDER_MAIN" > "$provider_log_file" 2>&1 &
    local provider_pid=$!
    echo "$provider_pid" > "$provider_pid_file"

    # 2. Wait until BOTH services are exported: the second export also writes
    #    the OrderService mapping the consumer needs for app-level routing.
    echo -n "[2/4] Waiting for application instance registered "
    local max_wait=15
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if grep -q "OrderService exported" "$provider_log_file" 2>/dev/null; then
            echo " ready (${waited}s)"
            break
        fi
        sleep 1
        waited=$((waited + 1))
        echo -n "."
    done
    if [ $waited -ge $max_wait ]; then
        echo " timeout (${max_wait}s), continuing..."
    fi

    # 3. Run consumer
    echo "[3/4] Running App3Consumer ..."
    echo "--------------------------------------------"
    local consumer_cp
    consumer_cp=$(build_classpath "$APP3_CONSUMER_MODULE")
    java -cp "$consumer_cp:$APP3_CONSUMER_MODULE/target/classes" \
        "$APP3_CONSUMER_MAIN"
    local consumer_exit=$?
    echo "--------------------------------------------"

    # 4. Stop provider and clean up
    echo "[4/4] Stopping App3Provider ..."
    if kill -0 "$provider_pid" 2>/dev/null; then
        kill "$provider_pid" 2>/dev/null
        wait "$provider_pid" 2>/dev/null || true
    fi
    rm -f "$provider_pid_file" "$provider_log_file"

    if [ $consumer_exit -ne 0 ]; then
        echo "Consumer exit code: $consumer_exit"
        exit $consumer_exit
    fi
    echo "=== Done ==="
}

# Point the shared raft one-shot at the nacos-leg sample modules, so the two
# legs are exercised by the same script rather than by a copy of it.
_use_raftx_leg() {
    RAFT_PROVIDER_MODULE="$RAFTX_PROVIDER_MODULE"
    RAFT_CONSUMER_MODULE="$RAFTX_CONSUMER_MODULE"
    RAFT_PROVIDER_MAIN="$RAFTX_PROVIDER_MAIN"
    RAFT_CONSUMER_MAIN="$RAFTX_CONSUMER_MAIN"
    RAFT_PROVIDER_PORT=20011
    RAFT_SVC_TAG="raftx-svc"
}

cmd_raftx() {
    _use_raftx_leg
    cmd_raft "$@"
}

# One-shot persistent-instance demo: RaftProvider registers DemoService with
# ephemeral=false (committed through harbor's Raft CP path), RaftConsumer
# discovers and calls it. Unlike the ephemeral harbor demo, the registration
# SURVIVES the provider shutdown at the end — that durability is the point.
cmd_raft() {
    ensure_built
    local harbor_port=19848
    local provider_port="${RAFT_PROVIDER_PORT:-20010}"
    local svc_tag="${RAFT_SVC_TAG:-raft-svc}"

    # Check if HarborServer is already running (lsof avoids triggering HTTP/2 handler errors)
    local harbor_running=0
    if command -v lsof >/dev/null 2>&1; then
        if lsof -Pi :$harbor_port -sTCP:LISTEN -t >/dev/null 2>&1; then
            harbor_running=1
        fi
    else
        if (echo > /dev/tcp/127.0.0.1/$harbor_port) 2>/dev/null; then
            harbor_running=1
        fi
    fi

    if [ $harbor_running -eq 0 ]; then
        echo "HarborServer is not running on port $harbor_port."
        echo ""
        echo "Please start it first:"
        echo "  ./run-sample.sh harbor-standalone          # single node"
        echo "  ./run-sample.sh harbor-cluster             # 3-node raft cluster"
        exit 1
    fi
    echo "HarborServer detected on port $harbor_port."

    local provider_pid_file=".provider-${svc_tag}.pid"
    local provider_log_file="provider-${svc_tag}.log"

    # 1. Start RaftProvider in background
    echo "[1/4] Starting RaftProvider port=$provider_port (persistent instance) ..."
    local provider_cp
    provider_cp=$(build_classpath "$RAFT_PROVIDER_MODULE")
    java -cp "$provider_cp:$RAFT_PROVIDER_MODULE/target/classes" \
        -Dport="$provider_port" \
        -Dharbor.port="$harbor_port" \
        "$RAFT_PROVIDER_MAIN" > "$provider_log_file" 2>&1 &
    local provider_pid=$!
    echo "$provider_pid" > "$provider_pid_file"

    # 2. Wait for the persistent registration to commit through raft
    echo -n "[2/4] Waiting for persistent registration "
    local max_wait=15
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if grep -q "Persistent instance registered" "$provider_log_file" 2>/dev/null; then
            echo " ready (${waited}s)"
            break
        fi
        sleep 1
        waited=$((waited + 1))
        echo -n "."
    done
    if [ $waited -ge $max_wait ]; then
        echo " timeout (${max_wait}s), continuing..."
    fi

    # 3. Run consumer
    echo "[3/4] Running RaftConsumer ..."
    echo "--------------------------------------------"
    local consumer_cp
    consumer_cp=$(build_classpath "$RAFT_CONSUMER_MODULE")
    java -cp "$consumer_cp:$RAFT_CONSUMER_MODULE/target/classes" \
        -Dharbor.port="$harbor_port" \
        "$RAFT_CONSUMER_MAIN"
    local consumer_exit=$?
    echo "--------------------------------------------"

    # 4. Stop provider and clean up. The registration itself stays in harbor's
    # raft log — kill -9 semantics: the leader's TCP probe flips it unhealthy.
    echo "[4/4] Stopping RaftProvider ..."
    if kill -0 "$provider_pid" 2>/dev/null; then
        kill "$provider_pid" 2>/dev/null
        wait "$provider_pid" 2>/dev/null || true
    fi
    rm -f "$provider_pid_file" "$provider_log_file"

    if [ $consumer_exit -ne 0 ]; then
        echo "Consumer exit code: $consumer_exit"
        exit $consumer_exit
    fi
    echo "=== Done ==="
    echo "Note: the persistent registration survives this shutdown (that is the demo)."
    echo "Inspect it:  curl http://localhost:$((harbor_port + 10))/api/raft"
    echo "Re-running this command replaces the instance idempotently."
}

cmd_wire_interop() {
    ensure_built
    local run_keepalive=0
    for arg in "$@"; do
        case "$arg" in
            --keepalive) run_keepalive=1 ;;
        esac
    done

    local interop_cp
    interop_cp=$(build_classpath "$WIRE_INTEROP_MODULE")
    interop_cp="$interop_cp:$WIRE_INTEROP_MODULE/target/classes"

    local passed=0
    local failed=0
    local total=0

    run_interop_demo() {
        local name="$1" main_class="$2"
        total=$((total + 1))
        echo ""
        echo "================================================================"
        echo "  [$total] $name"
        echo "================================================================"
        if java -cp "$interop_cp" "$main_class"; then
            echo ">>> $name PASSED"
            passed=$((passed + 1))
        else
            echo ">>> $name FAILED"
            failed=$((failed + 1))
        fi
    }

    echo "============================================"
    echo "  Wire Interop — Full Verification Suite"
    echo "============================================"

    # 1. GrpcCallWireDemo: grpc-java client -> jaws-wire server (self-contained)
    run_interop_demo "GrpcCallWireDemo (grpc-java -> jaws-wire)" "$WIRE_INTEROP_GRPC_CALL_WIRE"

    # 2. WireHealthDemo: gRPC health check (self-contained)
    run_interop_demo "WireHealthDemo (gRPC health check)" "$WIRE_INTEROP_HEALTH"

    # 3. ManagedChannelDemo: load balancing (self-contained)
    run_interop_demo "ManagedChannelDemo (load balancing)" "$WIRE_INTEROP_MANAGED"

    # 4. WireInterceptorDemo: server interceptor chain (grpc-java client ->
    #    jaws-wire server), self-contained
    run_interop_demo "WireInterceptorDemo (server interceptors)" "$WIRE_INTEROP_SERVER_INTERCEPTOR"

    # 5. WireClientInterceptorDemo: client interceptor chain across all four
    #    call shapes against a self-contained grpc-java server (self-contained)
    run_interop_demo "WireClientInterceptorDemo (client interceptors, 4 shapes)" "$WIRE_INTEROP_CLIENT_INTERCEPTOR"

    # 6. WireGoAwayDemo: grpc-java server with maxConnectionAge forces GOAWAY;
    #    WireClient must go READY -> IDLE and async-reconnect back to READY
    #    (self-contained)
    run_interop_demo "WireGoAwayDemo (server GOAWAY, async reconnect)" "$WIRE_INTEROP_GOAWAY"

    # 7. WireCallGrpcDemo: WireClient -> grpc-java server (needs GrpcServerMain)
    total=$((total + 1))
    echo ""
    echo "================================================================"
    echo "  [$total] WireCallGrpcDemo (WireClient -> grpc-java)"
    echo "================================================================"
    echo "[setup] Starting GrpcServerMain on port 50060 ..."
    java -cp "$interop_cp" "$WIRE_INTEROP_GRPC_SERVER" > /tmp/grpc-server-main.log 2>&1 &
    local grpc_pid=$!

    # Wait for grpc-java server ready
    local max_wait=10
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if (echo >/dev/tcp/127.0.0.1/50060) 2>/dev/null; then
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done
    if [ $waited -ge $max_wait ]; then
        echo ">>> WireCallGrpcDemo SKIPPED (GrpcServerMain failed to start)"
        failed=$((failed + 1))
    else
        echo "[setup] GrpcServerMain ready (PID=$grpc_pid, waited ${waited}s)"
        if java -cp "$interop_cp" "$WIRE_INTEROP_WIRE_CALL_GRPC"; then
            echo ">>> WireCallGrpcDemo PASSED"
            passed=$((passed + 1))
        else
            echo ">>> WireCallGrpcDemo FAILED"
            failed=$((failed + 1))
        fi
    fi
    kill "$grpc_pid" 2>/dev/null
    wait "$grpc_pid" 2>/dev/null || true
    rm -f /tmp/grpc-server-main.log

    # 7. (Optional) WireKeepaliveDemo: keepalive policy verification (~55s)
    if [ $run_keepalive -eq 1 ]; then
        run_interop_demo "WireKeepaliveDemo (keepalive policy, ~55s)" "$WIRE_INTEROP_KEEPALIVE"
    fi

    # Summary
    echo ""
    echo "============================================"
    echo "  Summary"
    echo "============================================"
    echo "  Passed : $passed"
    echo "  Failed : $failed"
    echo "  Total  : $total"
    if [ $run_keepalive -eq 0 ]; then
        echo "  (add --keepalive to also run WireKeepaliveDemo)"
    fi
    echo "============================================"

    if [ $failed -gt 0 ]; then
        exit 1
    fi
    echo "All wire-interop demos passed."
}

cmd_consumer() {
    ensure_built
    echo "Running ZkConsumer..."
    echo "--------------------------------------------"
    $MVN exec:java -pl "$CONSUMER_MODULE" -Dexec.mainClass="$CONSUMER_MAIN" -q
}

cmd_bench_store() {
    ensure_built
    local cp
    cp=$(build_classpath "$STORE_MODULE")
    echo "Running Benchmark [jaws-store kernel] path=${STORE_PATH:-/tmp/jaws-store-bench} msgSize=${MSG_SIZE:-128} queues=${QUEUES:-8} producers=${PRODUCERS:-16} steps=${STEPS:-50000,100000,200000,400000,0}"
    echo "--------------------------------------------"
    java -cp "$cp:$STORE_MODULE/target/classes" \
        -DstorePath="${STORE_PATH:-/tmp/jaws-store-bench}" \
        -DmsgSize="${MSG_SIZE:-128}" \
        -Dqueues="${QUEUES:-8}" \
        -Dproducers="${PRODUCERS:-16}" \
        -Dsteps="${STEPS:-50000,100000,200000,400000,0}" \
        -DstepSeconds="${STEP_SECONDS:-30}" \
        -DretentionMillis="${RETENTION_MILLIS:-60000}" \
        -DcleanupIntervalMillis="${CLEANUP_MILLIS:-3000}" \
        "$STORE_BENCH_MAIN"
}

cmd_bench_injvm() {
    ensure_built
    local threads="${THREADS:-4}"
    local warmup="${WARMUP:-5}"
    local duration="${DURATION:-10}"
    local sleep_ms="${SLEEP:-0}"
    echo "Running Benchmark [injvm] threads=$threads warmup=${warmup}s duration=${duration}s sleep=${sleep_ms}ms"
    echo "--------------------------------------------"
    $MVN exec:java -pl "$BENCHMARK_MODULE" \
        -Dexec.mainClass="$BENCHMARK_MAIN" \
        -Dprotocol=injvm \
        -Dthreads="$threads" \
        -Dwarmup="$warmup" \
        -Dduration="$duration" \
        -Dsleep="$sleep_ms" \
        -q
}

cmd_bench_jaws() {
    ensure_built
    local threads="${THREADS:-4}"
    local warmup="${WARMUP:-5}"
    local duration="${DURATION:-10}"
    local port="${PORT:-10010}"
    local serialization="${SERIALIZATION:-fastjson2}"
    local transport="${TRANSPORT:-netty}"
    local sleep_ms="${SLEEP:-0}"
    local role="${ROLE:-all}"
    local host="${HOST:-127.0.0.1}"
    local cp
    cp=$(build_classpath "$BENCHMARK_MODULE")
    cp="$cp:$BENCHMARK_MODULE/target/classes:jaws-samples/jaws-sample-injvm/target/classes"
    echo "Running Benchmark [jaws+$transport] role=$role threads=$threads warmup=${warmup}s duration=${duration}s port=$port serialization=$serialization transport=$transport sleep=${sleep_ms}ms host=$host"
    echo "--------------------------------------------"
    java -cp "$cp" \
        -Dprotocol=jaws \
        -Dtransport="$transport" \
        -Drole="$role" \
        -Dthreads="$threads" \
        -Dwarmup="$warmup" \
        -Dduration="$duration" \
        -Dport="$port" \
        -Dserialization="$serialization" \
        -Dsleep="$sleep_ms" \
        -Dhost="$host" \
        "$BENCHMARK_MAIN"
}

cmd_bench_wire() {
    ensure_built
    local threads="${THREADS:-4}"
    local warmup="${WARMUP:-5}"
    local duration="${DURATION:-10}"
    local port="${PORT:-50051}"
    local compression="${COMPRESSION:-}"
    local dispatch="${DISPATCH:-pipeline}"
    local role="${ROLE:-all}"
    local host="${HOST:-127.0.0.1}"
    local cp
    cp=$(build_classpath "$BENCHMARK_MODULE")
    cp="$cp:$BENCHMARK_MODULE/target/classes:jaws-samples/jaws-sample-injvm/target/classes"
    echo "Running Benchmark [wire] role=$role threads=$threads warmup=${warmup}s duration=${duration}s port=$port compression=${compression:-none} dispatch=$dispatch host=$host"
    echo "--------------------------------------------"
    java -cp "$cp" \
        -Drole="$role" \
        -Dthreads="$threads" \
        -Dwarmup="$warmup" \
        -Dduration="$duration" \
        -Dport="$port" \
        -Dcompression="$compression" \
        -Ddispatch="$dispatch" \
        -Dhost="$host" \
        "$WIRE_BENCHMARK_MAIN"
}

cmd_bench_grpc() {
    ensure_built
    local server="${SERVER:-wire}"
    local threads="${THREADS:-4}"
    local warmup="${WARMUP:-5}"
    local duration="${DURATION:-10}"
    local port="${PORT:-50051}"
    local role="${ROLE:-all}"
    local host="${HOST:-127.0.0.1}"
    local cp
    cp=$(build_classpath "$BENCHMARK_MODULE")
    cp="$cp:$BENCHMARK_MODULE/target/classes:jaws-samples/jaws-sample-injvm/target/classes"
    echo "Running Benchmark [grpc-java client -> $server] role=$role threads=$threads warmup=${warmup}s duration=${duration}s port=$port host=$host"
    echo "--------------------------------------------"
    java -cp "$cp" \
        -Dserver="$server" \
        -Drole="$role" \
        -Dthreads="$threads" \
        -Dwarmup="$warmup" \
        -Dduration="$duration" \
        -Dport="$port" \
        -Dhost="$host" \
        "$GRPC_BENCHMARK_MAIN"
}

# Main entry point
case "${1:-}" in
    build)       cmd_build ;;
    injvm)       cmd_injvm ;;
    provider)    cmd_provider "${2:-}" ;;
    provider-bg) cmd_provider_bg "${2:-}" ;;
    consumer)    cmd_consumer ;;
    stop)          cmd_stop ;;
    run)           cmd_run "${2:-}" ;;
    nacos)         cmd_run_nacos "${2:-}" ;;
    netty)         cmd_run_netty "${2:-}" ;;
    http2)         cmd_run_http2 "${2:-}" ;;
    wire)          cmd_run_wire "${2:-}" ;;
    interop)       shift; cmd_wire_interop "$@" ;;
    adaptive)      cmd_run_adaptive "${2:-}" ;;
    harbor)        cmd_harbor "${2:-}" "${3:-}" ;;
    app)           cmd_app "${2:-}" ;;
    app2)          cmd_app2 "${2:-}" "${3:-}" ;;
    app3)          cmd_app3 "${2:-}" ;;
    harbor-standalone) shift; cmd_harbor_standalone "$@" ;;
    harbor-cluster)  cmd_harbor_cluster ;;
    harbor-provider) shift; cmd_harbor_provider "$@" ;;
    harborx)         shift; cmd_harborx "$@" ;;
    harborx-provider) shift; cmd_harborx_provider "$@" ;;
    raft)            cmd_raft ;;
    raftx)           shift; cmd_raftx "$@" ;;
    store)           cmd_store "${2:-}" ;;
    store-cluster)   cmd_store_cluster "${2:-}" ;;
    bench-store) cmd_bench_store ;;
    bench-injvm) cmd_bench_injvm ;;
    bench-jaws)  cmd_bench_jaws ;;
    bench-wire)  cmd_bench_wire ;;
    bench-grpc)  cmd_bench_grpc ;;
    -h|--help|help|"") usage ;;
    *)
        echo "Unknown command: $1"
        usage
        exit 1
        ;;
esac
