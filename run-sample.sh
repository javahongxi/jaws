#!/bin/bash
#
# Jaws RPC Samples 启动脚本
#
# Usage: ./run-sample.sh <command> [options]
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MVN="./mvnw"
BENCHMARK_MODULE="jaws-samples/jaws-sample-benchmark"
INJVM_MODULE="jaws-samples/jaws-sample-injvm"
PROVIDER_MODULE="jaws-samples/jaws-sample-provider"
CONSUMER_MODULE="jaws-samples/jaws-sample-consumer"

INJVM_MAIN="org.hongxi.jaws.sample.injvm.InjvmRpcDemo"
PROVIDER_MAIN="org.hongxi.jaws.sample.provider.SampleProvider"
CONSUMER_MAIN="org.hongxi.jaws.sample.consumer.SampleConsumer"
BENCHMARK_MAIN="org.hongxi.jaws.sample.benchmark.RpcBenchmark"

usage() {
    cat <<'EOF'

  Jaws RPC Samples

  Usage: ./run-sample.sh <command> [options]

  Commands:
    build              编译项目
    injvm              运行 InjvmRpcDemo（injvm 协议，无需 ZK）
    provider [port]    启动 SampleProvider（需要 ZK 在 127.0.0.1:2181）
                       port 默认 10000，设为 -1 则从 10000 开始自动分配
    provider-bg [port] 后台启动 Provider，PID/日志按端口区分（如 .provider-10000.pid）
                       port 为 -1 时自动分配端口，文件后缀为 auto-{序号}
    stop               停止所有后台进程并清理 pid/log 文件
    consumer           运行 SampleConsumer（需要先启动 provider）
    bench-injvm        性能测试 - injvm 协议
    bench-jaws         性能测试 - jaws+netty 协议

  Benchmark Options (通过环境变量传入):
    THREADS            并发线程数（默认 4）
    WARMUP             预热秒数（默认 5）
    DURATION           测量秒数（默认 10）
    PORT               jaws 协议端口（默认 10010，仅 bench-jaws）

  Examples:
    ./run-sample.sh build
    ./run-sample.sh injvm
    ./run-sample.sh provider
    ./run-sample.sh provider 10001
    ./run-sample.sh provider-bg        # 后台启动，端口 10000
    ./run-sample.sh provider-bg 10001  # 后台启动，端口 10001
    ./run-sample.sh provider-bg -1     # 后台启动，自动分配端口
    ./run-sample.sh stop               # 停止所有后台进程
    ./run-sample.sh consumer
    ./run-sample.sh bench-injvm
    THREADS=8 DURATION=20 ./run-sample.sh bench-jaws

EOF
}

ensure_built() {
    if [ ! -d "jaws-core/target/classes" ]; then
        echo "项目未编译，正在编译..."
        $MVN install -DskipTests -q
    fi
}

#
# Build full classpath for java -cp (project modules + external dependencies)
#
build_classpath() {
    local module="$1"
    local deps
    deps=$($MVN -pl "$module" dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout -q 2>/dev/null)
    echo "jaws-core/target/classes:jaws-transport-netty/target/classes:jaws-registry-zookeeper/target/classes:jaws-samples/jaws-sample-api/target/classes:$deps"
}

cmd_build() {
    echo "编译项目..."
    $MVN clean install -DskipTests -q
    echo "编译完成。"
}

cmd_injvm() {
    ensure_built
    echo "运行 InjvmRpcDemo..."
    echo "--------------------------------------------"
    $MVN exec:java -pl "$INJVM_MODULE" -Dexec.mainClass="$INJVM_MAIN" -q
}

cmd_provider() {
    ensure_built
    local port="${1:-10000}"
    echo "启动 SampleProvider（jaws + ZooKeeper）port=$port"
    echo "请确保 ZooKeeper 已在 127.0.0.1:2181 运行"
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

    # 确定文件后缀：固定端口用端口号，-1 用 auto-{序号}
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

    echo "后台启动 SampleProvider port=$port ..."
    java -cp "$cp:$PROVIDER_MODULE/target/classes" \
        -Dport="$port" \
        "$PROVIDER_MAIN" > "$log_file" 2>&1 &
    local pid=$!
    echo "$pid" > "$pid_file"
    echo "Provider started (PID=$pid), log: $log_file"
    echo "停止: kill \$(cat $pid_file)"
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

cmd_consumer() {
    ensure_built
    echo "运行 SampleConsumer..."
    echo "--------------------------------------------"
    $MVN exec:java -pl "$CONSUMER_MODULE" -Dexec.mainClass="$CONSUMER_MAIN" -q
}

cmd_bench_injvm() {
    ensure_built
    local threads="${THREADS:-4}"
    local warmup="${WARMUP:-5}"
    local duration="${DURATION:-10}"
    echo "运行 Benchmark [injvm] threads=$threads warmup=${warmup}s duration=${duration}s"
    echo "--------------------------------------------"
    $MVN exec:java -pl "$BENCHMARK_MODULE" \
        -Dexec.mainClass="$BENCHMARK_MAIN" \
        -Dprotocol=injvm \
        -Dthreads="$threads" \
        -Dwarmup="$warmup" \
        -Dduration="$duration" \
        -q
}

cmd_bench_jaws() {
    ensure_built
    local threads="${THREADS:-4}"
    local warmup="${WARMUP:-5}"
    local duration="${DURATION:-10}"
    local port="${PORT:-10010}"
    echo "运行 Benchmark [jaws+netty] threads=$threads warmup=${warmup}s duration=${duration}s port=$port"
    echo "--------------------------------------------"
    $MVN exec:java -pl "$BENCHMARK_MODULE" \
        -Dexec.mainClass="$BENCHMARK_MAIN" \
        -Dprotocol=jaws \
        -Dthreads="$threads" \
        -Dwarmup="$warmup" \
        -Dduration="$duration" \
        -Dport="$port" \
        -q
}

# 主入口
case "${1:-}" in
    build)       cmd_build ;;
    injvm)       cmd_injvm ;;
    provider)    cmd_provider "${2:-}" ;;
    provider-bg) cmd_provider_bg "${2:-}" ;;
    stop)          cmd_stop ;;
    consumer)    cmd_consumer ;;
    bench-injvm) cmd_bench_injvm ;;
    bench-jaws)  cmd_bench_jaws ;;
    -h|--help|help|"") usage ;;
    *)
        echo "未知命令: $1"
        usage
        exit 1
        ;;
esac
