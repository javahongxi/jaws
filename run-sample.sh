#!/bin/bash
#
# Jaws RPC Samples launcher script
#
# Usage: ./run-sample.sh <command> [options]
#

set -e

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
ADAPTIVE_PROVIDER_MODULE="jaws-samples/jaws-sample-adaptive-provider"
ADAPTIVE_CONSUMER_MODULE="jaws-samples/jaws-sample-adaptive-consumer"

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
ADAPTIVE_PROVIDER_MAIN="org.hongxi.jaws.sample.adaptive.provider.AdaptiveProvider"
ADAPTIVE_CONSUMER_MAIN="org.hongxi.jaws.sample.adaptive.consumer.AdaptiveConsumer"

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
    stop               Stop all background processes and clean up pid/log files
    run [port]         One-shot ZK sample: start provider -> run consumer -> stop provider
    nacos [port]       One-shot Nacos sample (requires Nacos at 127.0.0.1:8848)
    netty [port]       One-shot Netty direct-connect sample (no registry required)
    http2 [port]       One-shot HTTP/2 direct-connect sample (incl. Server Streaming, no registry required)
    wire [port]        One-shot Wire (gRPC wire format) direct-connect sample (no registry required, default port 50051)
    adaptive [port]    One-shot Adaptive direct-connect sample (single port, multi-protocol, no registry required)
    consumer           Run ZkConsumer (provider must be started first)
    bench-injvm        Benchmark - injvm protocol
    bench-jaws         Benchmark - jaws protocol (default netty transport)
    bench-wire         Benchmark - wire protocol (gRPC wire format over HTTP/2)

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
    HOST               Provider address (default 127.0.0.1, separate-process mode only)

  Examples:
    ./run-sample.sh build
    ./run-sample.sh injvm
    ./run-sample.sh provider
    ./run-sample.sh provider 10001
    ./run-sample.sh provider-bg        # Start in background, port 10000
    ./run-sample.sh provider-bg 10001  # Start in background, port 10001
    ./run-sample.sh provider-bg -1     # Start in background, auto-allocate port
    ./run-sample.sh stop               # Stop all background processes
    ./run-sample.sh run                # One-shot ZK provider + consumer
    ./run-sample.sh nacos              # One-shot Nacos provider + consumer
    ./run-sample.sh netty              # One-shot Netty direct-connect provider + consumer
    ./run-sample.sh http2              # One-shot HTTP/2 direct-connect (with streaming) provider + consumer
    ./run-sample.sh wire               # One-shot Wire direct-connect provider + consumer
    ./run-sample.sh adaptive           # One-shot Adaptive direct-connect (multi-protocol) provider + consumer
    ./run-sample.sh consumer
    ./run-sample.sh bench-injvm
    THREADS=8 DURATION=20 ./run-sample.sh bench-jaws
    SERIALIZATION=hessian2 ./run-sample.sh bench-jaws
    TRANSPORT=http2 THREADS=20 DURATION=40 ./run-sample.sh bench-jaws
    SLEEP=5 ./run-sample.sh bench-jaws       # Simulate 5ms business latency
    THREADS=20 DURATION=40 ./run-sample.sh bench-wire
    COMPRESSION=gzip ./run-sample.sh bench-wire
    # Separate-process benchmark: run in two terminals (bench-jaws only)
    ROLE=provider ./run-sample.sh bench-jaws
    ROLE=consumer THREADS=20 ./run-sample.sh bench-jaws

EOF
}

ensure_built() {
    # mvn exec:java / java -cp rely on installed JARs and module target/classes,
    # so re-install whenever any module's sources are newer than the installed jaws-core JAR.
    local jar="$HOME/.m2/repository/org/hongxi/jaws-core/1.0.0-SNAPSHOT/jaws-core-1.0.0-SNAPSHOT.jar"
    local need_build=0
    if [ ! -f "$jar" ]; then
        need_build=1
    else
        for src in jaws-core/src/main/java jaws-wire/src/main/java jaws-registry-zookeeper/src/main/java jaws-registry-nacos/src/main/java jaws-samples/*/src/main/java jaws-samples/jaws-sample-gray/*/src/main/java; do
            if [ "$(find $src -newer "$jar" -print -quit 2>/dev/null)" ]; then
                need_build=1
                break
            fi
        done
    fi
    if [ $need_build -eq 1 ]; then
        echo "Project not built or sources updated, building and installing..."
        $MVN install -DskipTests -q
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
    local project_cp="jaws-core/target/classes:jaws-samples/jaws-sample-api/target/classes"
    case "$deps" in *curator*) project_cp="$project_cp:jaws-registry-zookeeper/target/classes" ;; esac
    case "$deps" in *nacos-client*) project_cp="$project_cp:jaws-registry-nacos/target/classes" ;; esac
    case "$deps" in *protobuf-java*) project_cp="$project_cp:jaws-wire/target/classes:jaws-samples/jaws-sample-wire-api/target/classes" ;; esac
    echo "$project_cp:$deps"
}

cmd_build() {
    echo "Building project..."
    $MVN clean install -DskipTests -q
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
    for main_class in "$PROVIDER_MAIN" "$BENCHMARK_MAIN"; do
        local pids
        pids=$(jps -l 2>/dev/null | grep "$main_class" | awk '{print $1}')
        for pid in $pids; do
            kill "$pid" 2>/dev/null
            echo "Stopped process $pid ($main_class)"
            count=$((count + 1))
        done
    done

    # 3. Clean up log files
    for log_file in provider-*.log; do
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

    # 1. Start provider in background
    echo "[1/4] Starting Provider port=$port ..."
    java -cp "$cp:$provider_module/target/classes" \
        -Dport="$port" \
        "$provider_main" > "$log_file" 2>&1 &
    local pid=$!
    echo "$pid" > "$pid_file"

    # 2. Wait for provider to accept connections on the port (max 15 seconds)
    echo -n "[2/4] Waiting for Provider ready "
    local max_wait=15
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if (echo >/dev/tcp/127.0.0.1/$port) 2>/dev/null; then
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
    consumer_cp=$(build_classpath "$consumer_module")
    java -cp "$consumer_cp:$consumer_module/target/classes" \
        -DdirectUrl="127.0.0.1:$port" \
        "$consumer_main"
    local consumer_exit=$?
    echo "--------------------------------------------"

    # 4. Stop provider and clean up
    echo "[4/4] Stopping Provider ..."
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null || true
    fi
    rm -f "$pid_file" "$log_file"

    if [ $consumer_exit -ne 0 ]; then
        echo "Consumer exit code: $consumer_exit"
        exit $consumer_exit
    fi
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

cmd_consumer() {
    ensure_built
    echo "Running ZkConsumer..."
    echo "--------------------------------------------"
    $MVN exec:java -pl "$CONSUMER_MODULE" -Dexec.mainClass="$CONSUMER_MAIN" -q
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
    local role="${ROLE:-all}"
    local host="${HOST:-127.0.0.1}"
    local cp
    cp=$(build_classpath "$BENCHMARK_MODULE")
    cp="$cp:$BENCHMARK_MODULE/target/classes:jaws-samples/jaws-sample-injvm/target/classes"
    echo "Running Benchmark [wire] role=$role threads=$threads warmup=${warmup}s duration=${duration}s port=$port compression=${compression:-none} host=$host"
    echo "--------------------------------------------"
    java -cp "$cp" \
        -Drole="$role" \
        -Dthreads="$threads" \
        -Dwarmup="$warmup" \
        -Dduration="$duration" \
        -Dport="$port" \
        -Dcompression="$compression" \
        -Dhost="$host" \
        "$WIRE_BENCHMARK_MAIN"
}

# Main entry point
case "${1:-}" in
    build)       cmd_build ;;
    injvm)       cmd_injvm ;;
    provider)    cmd_provider "${2:-}" ;;
    provider-bg) cmd_provider_bg "${2:-}" ;;
    stop)          cmd_stop ;;
    run)           cmd_run "${2:-}" ;;
    nacos)         cmd_run_nacos "${2:-}" ;;
    netty)         cmd_run_netty "${2:-}" ;;
    http2)         cmd_run_http2 "${2:-}" ;;
    wire)          cmd_run_wire "${2:-}" ;;
    adaptive)      cmd_run_adaptive "${2:-}" ;;
    consumer)    cmd_consumer ;;
    bench-injvm) cmd_bench_injvm ;;
    bench-jaws)  cmd_bench_jaws ;;
    bench-wire)  cmd_bench_wire ;;
    -h|--help|help|"") usage ;;
    *)
        echo "Unknown command: $1"
        usage
        exit 1
        ;;
esac
