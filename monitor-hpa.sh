2. HPA监控脚本 (monitor-hpa.sh)


#!/bin/bash
set -euo pipefail

# 监控HPA状态
monitor_hpa() {
    echo "📊 HPA监控面板"
    echo "="$(printf '=%.0s' {1..50})
    
    while true; do
        clear
        echo "🕐 $(date '+%Y-%m-%d %H:%M:%S')"
        echo ""
        
        # 显示HPA状态
        echo "📈 HPA状态:"
        kubectl get hpa hpa-demo -o wide
        
        echo ""
        echo "📦 Pod状态:"
        kubectl get pods -l app=hpa-demo -o wide
        
        echo ""
        echo "💻 资源使用:"
        kubectl top pods -l app=hpa-demo
        
        echo ""
        echo "📋 事件监控:"
        kubectl get events --field-selector involvedObject.name=hpa-demo --sort-by='.lastTimestamp' | tail -5
        
        sleep 5
    done
}

# 异常检测
detect_anomalies() {
    while true; do
        # 检查Pod重启次数
        local restart_count=$(kubectl get pods -l app=hpa-demo -o json | \
            jq -r '.items[].status.containerStatuses[0].restartCount' | \
            awk '{sum+=$1} END {print sum}')
        
        if [[ $restart_count -gt 10 ]]; then
            echo "⚠️  警告: Pod重启次数过多: $restart_count"
        fi
        
        # 检查HPA状态
        local hpa_status=$(kubectl get hpa hpa-demo -o json | \
            jq -r '.status.conditions[] | select(.type=="AbleToScale") | .status')
        
        if [[ "$hpa_status" != "True" ]]; then
            echo "❌ HPA无法缩放，请检查资源限制"
        fi
        
        sleep 30
    done
}

main() {
    # 启动监控
    monitor_hpa &
    local monitor_pid=$!
    
    # 启动异常检测
    detect_anomalies &
    local anomaly_pid=$!
    
    # 等待用户中断
    trap "kill $monitor_pid $anomaly_pid; exit 0" INT TERM
    wait
}

main "$@"
