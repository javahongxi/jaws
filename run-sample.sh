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

INJVM_MAIN="org.hongxi.jaws.sample.injvm.InjvmRpcDemo"
PROVIDER_MAIN="org.hongxi.jaws.sample.zk.provider.ZkProvider"
CONSUMER_MAIN="org.hongxi.jaws.sample.zk.consumer.ZkConsumer"
BENCHMARK_MAIN="org.hongxi.jaws.sample.benchmark.RpcBenchmark"
NACOS_PROVIDER_MAIN="org.hongxi.jaws.sample.nacos.provider.NacosProvider"
NACOS_CONSUMER_MAIN="org.hongxi.jaws.sample.nacos.consumer.NacosConsumer"
NETTY_PROVIDER_MAIN="org.hongxi.jaws.sample.netty.provider.NettyProvider"
NETTY_CONSUMER_MAIN="org.hongxi.jaws.sample.netty.consumer.NettyConsumer"
HTTP2_PROVIDER_MAIN="org.hongxi.jaws.sample.http2.provider.Http2Provider"
HTTP2_CONSUMER_MAIN="org.hongxi.jaws.sample.http2.consumer.Http2Consumer"
WIRE_PROVIDER_MAIN="org.hongxi.jaws.sample.wire.provider.WireProvider"
WIRE_CONSUMER_MAIN="org.hongxi.jaws.sample.wire.consumer.WireConsumer"

usage() {
    cat <<'EOF'

  Jaws RPC Samples

  Usage: ./run-sample.sh <command> [options]

  Commands:
    build              编译项目
    injvm              运行 InjvmRpcDemo（injvm 协议，无需 ZK）
    provider [port]    启动 ZkProvider（需要 ZK 在 127.0.0.1:2181）
                       port 默认 10000，设为 -1 则从 10000 开始自动分配
    provider-bg [port] 后台启动 Provider，PID/日志按端口区分（如 .provider-10000.pid）
                       port 为 -1 时自动分配端口，文件后缀为 auto-{序号}
    stop               停止所有后台进程并清理 pid/log 文件
    run [port]         一次性运行 ZK 示例：启动 provider → 运行 consumer → 停止 provider
    nacos [port]       一次性运行 Nacos 示例（需要 Nacos 在 127.0.0.1:8848）
    netty [port]       一次性运行 Netty 直连示例（无需注册中心）
    http2 [port]       一次性运行 HTTP/2 直连示例（含 Server Streaming，无需注册中心）
    wire [port]        一次性运行 Wire (gRPC wire format) 直连示例（无需注册中心，默认端口 50051）
    consumer           运行 ZkConsumer（需要先启动 provider）
    bench-injvm        性能测试 - injvm 协议
    bench-jaws         性能测试 - jaws+netty 协议

  Benchmark Options (通过环境变量传入):
    THREADS            并发线程数（默认 4）
    WARMUP             预热秒数（默认 5）
    DURATION           测量秒数（默认 10）
    PORT               jaws 协议端口（默认 10010，仅 bench-jaws）
    SERIALIZATION      序列化方式（默认 fastjson2，仅 bench-jaws）
    SLEEP              Provider 端模拟业务耗时毫秒数（默认 0，不模拟）
    ROLE               运行角色（默认 all 同进程；provider/consumer 分进程，仅 bench-jaws）
    HOST               provider 地址（默认 127.0.0.1，仅分进程模式）

  Examples:
    ./run-sample.sh build
    ./run-sample.sh injvm
    ./run-sample.sh provider
    ./run-sample.sh provider 10001
    ./run-sample.sh provider-bg        # 后台启动，端口 10000
    ./run-sample.sh provider-bg 10001  # 后台启动，端口 10001
    ./run-sample.sh provider-bg -1     # 后台启动，自动分配端口
    ./run-sample.sh stop               # 停止所有后台进程
    ./run-sample.sh run                # 一键运行 ZK provider + consumer
    ./run-sample.sh nacos              # 一键运行 Nacos provider + consumer
    ./run-sample.sh netty              # 一键运行 Netty 直连 provider + consumer
    ./run-sample.sh http2              # 一键运行 HTTP/2 直连（含流式）provider + consumer
    ./run-sample.sh wire               # 一键运行 Wire 直连 provider + consumer
    ./run-sample.sh consumer
    ./run-sample.sh bench-injvm
    THREADS=8 DURATION=20 ./run-sample.sh bench-jaws
    SERIALIZATION=hessian2 ./run-sample.sh bench-jaws
    SLEEP=5 ./run-sample.sh bench-jaws       # 模拟 5ms 业务耗时
    # 分进程压测：两个终端分别执行（仅 bench-jaws）
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
        echo "项目未编译或源码已更新，正在编译安装..."
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
    echo "启动 ZkProvider（jaws + ZooKeeper）port=$port"
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

    echo "后台启动 ZkProvider port=$port ..."
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

#
# Generic one-shot run: start provider -> run consumer -> stop provider.
# $1 name  $2 provider module  $3 provider main  $4 consumer module  $5 consumer main
# $6 default port  $7 expected "exported" log lines  $8 prerequisite hint  $9 port
#
run_pair() {
    local name="$1" provider_module="$2" provider_main="$3" consumer_module="$4" consumer_main="$5"
    local default_port="$6" expected_exports="$7" hint="$8" port="${9:-}"
    [ -z "$port" ] && port="$default_port"

    ensure_built
    local cp
    cp=$(build_classpath "$provider_module")
    local pid_file=".provider-${name}.pid"
    local log_file="provider-${name}.log"

    echo "=== 一键运行 ($name) ==="
    [ -n "$hint" ] && echo "$hint"
    echo ""

    # 1. 后台启动 provider
    echo "[1/4] 启动 Provider port=$port ..."
    java -cp "$cp:$provider_module/target/classes" \
        -Dport="$port" \
        "$provider_main" > "$log_file" 2>&1 &
    local pid=$!
    echo "$pid" > "$pid_file"

    # 2. 等待 provider 完成服务发布（轮询日志，最多等 15 秒）
    echo -n "[2/4] 等待 Provider 就绪 "
    local max_wait=15
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if grep -q "exported" "$log_file" 2>/dev/null; then
            local count
            count=$(grep -c "exported" "$log_file")
            if [ "$count" -ge "$expected_exports" ]; then
                echo " 就绪 (${waited}s)"
                break
            fi
        fi
        sleep 1
        waited=$((waited + 1))
        echo -n "."
    done
    if [ $waited -ge $max_wait ]; then
        echo " 超时 (${max_wait}s)，继续运行..."
    fi

    # 3. 运行 consumer
    echo "[3/4] 运行 Consumer ..."
    echo "--------------------------------------------"
    local consumer_cp
    consumer_cp=$(build_classpath "$consumer_module")
    java -cp "$consumer_cp:$consumer_module/target/classes" \
        -DdirectUrl="127.0.0.1:$port" \
        "$consumer_main"
    local consumer_exit=$?
    echo "--------------------------------------------"

    # 4. 停止 provider 并清理
    echo "[4/4] 停止 Provider ..."
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null || true
    fi
    rm -f "$pid_file" "$log_file"

    if [ $consumer_exit -ne 0 ]; then
        echo "Consumer 退出码: $consumer_exit"
        exit $consumer_exit
    fi
    echo "=== 完成 ==="
}

cmd_run() {
    run_pair "zk" "$PROVIDER_MODULE" "$PROVIDER_MAIN" "$CONSUMER_MODULE" "$CONSUMER_MAIN" \
        10000 2 "请确保 ZooKeeper 已在 127.0.0.1:2181 运行" "${1:-}"
}

cmd_run_nacos() {
    run_pair "nacos" "$NACOS_PROVIDER_MODULE" "$NACOS_PROVIDER_MAIN" "$NACOS_CONSUMER_MODULE" "$NACOS_CONSUMER_MAIN" \
        10000 2 "请确保 Nacos 已在 127.0.0.1:8848 运行" "${1:-}"
}

cmd_run_netty() {
    run_pair "netty" "$NETTY_PROVIDER_MODULE" "$NETTY_PROVIDER_MAIN" "$NETTY_CONSUMER_MODULE" "$NETTY_CONSUMER_MAIN" \
        10000 2 "" "${1:-}"
}

cmd_run_http2() {
    run_pair "http2" "$HTTP2_PROVIDER_MODULE" "$HTTP2_PROVIDER_MAIN" "$HTTP2_CONSUMER_MODULE" "$HTTP2_CONSUMER_MAIN" \
        10000 3 "" "${1:-}"
}

cmd_run_wire() {
    run_pair "wire" "$WIRE_PROVIDER_MODULE" "$WIRE_PROVIDER_MAIN" "$WIRE_CONSUMER_MODULE" "$WIRE_CONSUMER_MAIN" \
        50051 1 "" "${1:-}"
}

cmd_consumer() {
    ensure_built
    echo "运行 ZkConsumer..."
    echo "--------------------------------------------"
    $MVN exec:java -pl "$CONSUMER_MODULE" -Dexec.mainClass="$CONSUMER_MAIN" -q
}

cmd_bench_injvm() {
    ensure_built
    local threads="${THREADS:-4}"
    local warmup="${WARMUP:-5}"
    local duration="${DURATION:-10}"
    local sleep_ms="${SLEEP:-0}"
    echo "运行 Benchmark [injvm] threads=$threads warmup=${warmup}s duration=${duration}s sleep=${sleep_ms}ms"
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
    local sleep_ms="${SLEEP:-0}"
    local role="${ROLE:-all}"
    local host="${HOST:-127.0.0.1}"
    echo "运行 Benchmark [jaws+netty] role=$role threads=$threads warmup=${warmup}s duration=${duration}s port=$port serialization=$serialization sleep=${sleep_ms}ms host=$host"
    echo "--------------------------------------------"
    $MVN exec:java -pl "$BENCHMARK_MODULE" \
        -Dexec.mainClass="$BENCHMARK_MAIN" \
        -Dprotocol=jaws \
        -Drole="$role" \
        -Dthreads="$threads" \
        -Dwarmup="$warmup" \
        -Dduration="$duration" \
        -Dport="$port" \
        -Dserialization="$serialization" \
        -Dsleep="$sleep_ms" \
        -Dhost="$host" \
        -q
}

# 主入口
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
