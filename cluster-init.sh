# Kubernetes集群初始化脚本

#!/bin/bash
set -euo pipefail

# 错误处理函数
handle_error() {
    echo "❌ 错误发生在第 $1 行，退出码: $2"
    echo "详细错误信息:"
    echo "命令: ${BASH_COMMAND}"
    exit 1
}

trap 'handle_error ${LINENO} $?' ERR

# 检查系统要求
check_prerequisites() {
    echo "🔍 检查系统要求..."
    
    # 检查内存
    local mem_gb=$(free -g | awk '/^Mem:/{print $2}')
    if [[ $mem_gb -lt 4 ]]; then
        echo "⚠️  警告: 推荐至少4GB内存，当前: ${mem_gb}GB"
    fi
    
    # 检查CPU核心
    local cpu_cores=$(nproc)
    if [[ $cpu_cores -lt 2 ]]; then
        echo "⚠️  警告: 推荐至少2个CPU核心，当前: ${cpu_cores}"
    fi
    
    # 检查必需工具
    local required_tools=("docker" "kubectl" "helm")
    for tool in "${required_tools[@]}"; do
        if ! command -v "$tool" &>/dev/null; then
            echo "❌ 错误: 未找到 $tool 命令"
            exit 1
        fi
    done
}

# 使用Kind创建K8s集群
create_kind_cluster() {
    echo "🚀 创建Kind集群..."
    
    # 检查Kind是否安装
    if ! command -v kind &>/dev/null; then
        echo "📦 安装Kind..."
        curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.20.0/kind-linux-amd64
        chmod +x ./kind
        sudo mv ./kind /usr/local/bin/
    fi
    
    # 创建集群配置文件
    cat > kind-config.yaml <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  kubeadmConfigPatches:
  - |
    kind: InitConfiguration
    nodeRegistration:
      kubeletExtraArgs:
        node-labels: "ingress-ready=true"
  extraPortMappings:
  - containerPort: 80
    hostPort: 80
    protocol: TCP
  - containerPort: 443
    hostPort: 443
    protocol: TCP
- role: worker
- role: worker
EOF
    
    # 创建集群
    kind create cluster --name demo-cluster --config kind-config.yaml --wait 5m || {
        echo "❌ 集群创建失败"
        kind delete cluster --name demo-cluster
        exit 1
    }
    
    echo "✅ 集群创建成功"
}

# 安装Metrics Server
install_metrics_server() {
    echo "📊 安装Metrics Server..."
    
    kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
    
    # 等待Metrics Server就绪
    local timeout=180
    local interval=5
    for ((i=0; i<timeout/interval; i++)); do
        if kubectl get pods -n kube-system -l k8s-app=metrics-server | grep -q "Running"; then
            echo "✅ Metrics Server已就绪"
            return 0
        fi
        echo "⏳ 等待Metrics Server启动... ($((i*interval))秒)"
        sleep $interval
    done
    
    echo "❌ Metrics Server启动超时"
    return 1
}

# 部署演示应用
deploy_demo_app() {
    echo "📦 部署演示应用..."
    
    cat > hpa-demo.yaml <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hpa-demo-app
  labels:
    app: hpa-demo
spec:
  replicas: 2
  selector:
    matchLabels:
      app: hpa-demo
  template:
    metadata:
      labels:
        app: hpa-demo
    spec:
      containers:
      - name: stress-app
        image: polinux/stress
        resources:
          requests:
            cpu: "200m"
            memory: "100Mi"
          limits:
            cpu: "500m"
            memory: "200Mi"
        command: ["stress"]
        args: ["--cpu", "2", "--timeout", "600"]
        readinessProbe:
          exec:
            command:
            - sh
            - -c
            - 'test $(ps aux | grep -c "[s]tress") -gt 0'
          initialDelaySeconds: 5
          periodSeconds: 5
        livenessProbe:
          exec:
            command:
            - sh
            - -c
            - 'test $(ps aux | grep -c "[s]tress") -gt 0'
          initialDelaySeconds: 10
          periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: hpa-demo-service
spec:
  selector:
    app: hpa-demo
  ports:
  - port: 8080
    targetPort: 8080
EOF
    
    kubectl apply -f hpa-demo.yaml
    
    # 等待应用就绪
    kubectl wait --for=condition=available --timeout=300s deployment/hpa-demo-app
}

# 配置HPA
configure_hpa() {
    echo "⚖️ 配置HPA..."
    
    cat > hpa-config.yaml <<EOF
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: hpa-demo
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: hpa-demo-app
  minReplicas: 2
  maxReplicas: 10
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
      - type: Percent
        value: 100
        periodSeconds: 15
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 50
EOF
    
    kubectl apply -f hpa-config.yaml
    
    # 验证HPA
    sleep 10
    if ! kubectl get hpa hpa-demo; then
        echo "❌ HPA创建失败"
        return 1
    fi
}

# 生成负载测试
generate_load() {
    echo "🔥 生成测试负载..."
    
    # 创建负载生成器
    cat > load-generator.yaml <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: load-generator
spec:
  template:
    spec:
      containers:
      - name: load-gen
        image: busybox
        command: ["/bin/sh", "-c"]
        args:
        - |
          for i in \$(seq 1 100); do
            wget -q -O- http://hpa-demo-service:8080 || true
            sleep 0.01
          done
      restartPolicy: Never
  backoffLimit: 0
EOF
    
    kubectl apply -f load-generator.yaml
    
    echo "📈 监控HPA状态:"
    echo "   kubectl get hpa -w"
    echo "   kubectl get pods -w"
}

main() {
    echo "🎬 开始Kubernetes HPA演示..."
    
    check_prerequisites
    create_kind_cluster
    install_metrics_server
    deploy_demo_app
    configure_hpa
    generate_load
    
    echo ""
    echo "✅ 演示准备完成！"
    echo ""
    echo "📋 可用命令:"
    echo "   1. 查看HPA状态: kubectl get hpa -w"
    echo "   2. 查看Pod状态: kubectl get pods -w"
    echo "   3. 查看Metrics: kubectl top pods"
    echo "   4. 清理资源: kind delete cluster --name demo-cluster"
}

main "$@"
