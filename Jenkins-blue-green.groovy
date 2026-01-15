二、Jenkins蓝绿部署配置
1. Jenkins流水线配置 (Jenkinsfile-blue-green)


pipeline {
    agent any
    
    environment {
        DOCKER_REGISTRY = credentials('docker-registry')
        KUBECONFIG = credentials('kubeconfig')
        APP_NAME = 'demo-app'
        NAMESPACE = 'production'
        DEPLOYMENT_TIMEOUT = '300'
        HEALTH_CHECK_TIMEOUT = '120'
    }
    
    parameters {
        choice(
            name: 'DEPLOYMENT_TYPE',
            choices: ['blue-green', 'canary'],
            description: '选择部署策略'
        )
        string(
            name: 'IMAGE_TAG',
            defaultValue: "${BUILD_NUMBER}",
            description: '镜像标签'
        )
    }
    
    stages {
        stage('环境验证') {
            steps {
                script {
                    // 验证Kubernetes连接
                    sh '''
                    kubectl cluster-info || {
                        echo "❌ Kubernetes集群连接失败"
                        exit 1
                    }
                    
                    # 验证命名空间
                    if ! kubectl get namespace ${NAMESPACE} &> /dev/null; then
                        echo "📦 创建命名空间 ${NAMESPACE}"
                        kubectl create namespace ${NAMESPACE}
                    fi
                    '''
                    
                    // 验证Docker仓库
                    sh '''
                    docker login ${DOCKER_REGISTRY} || {
                        echo "❌ Docker仓库登录失败"
                        exit 1
                    }
                    '''
                }
            }
        }
        
        stage('代码构建与测试') {
            parallel {
                stage('代码检查') {
                    steps {
                        sh '''
                        # 执行静态代码分析
                        echo "🔍 执行代码检查..."
                        # 这里可以集成SonarQube等工具
                        '''
                    }
                }
                stage('单元测试') {
                    steps {
                        sh '''
                        echo "🧪 执行单元测试..."
                        # 执行测试并生成报告
                        go test ./... -v -coverprofile=coverage.out || {
                            echo "❌ 单元测试失败"
                            exit 1
                        }
                        '''
                    }
                }
            }
        }
        
        stage('构建镜像') {
            steps {
                script {
                    try {
                        sh """
                        docker build \
                            --build-arg BUILD_NUMBER=${BUILD_NUMBER} \
                            -t ${DOCKER_REGISTRY}/${APP_NAME}:${IMAGE_TAG} \
                            -t ${DOCKER_REGISTRY}/${APP_NAME}:latest \
                            .
                        """
                    } catch (Exception e) {
                        error("❌ 镜像构建失败: ${e.getMessage()}")
                    }
                }
            }
        }
        
        stage('安全扫描') {
            steps {
                script {
                    // 使用Trivy进行漏洞扫描
                    sh '''
                    docker run --rm \
                        -v /var/run/docker.sock:/var/run/docker.sock \
                        aquasec/trivy image \
                        --severity HIGH,CRITICAL \
                        ${DOCKER_REGISTRY}/${APP_NAME}:${IMAGE_TAG} || {
                        echo "⚠️  发现高危漏洞，请检查"
                        # 这里可以根据策略决定是否继续
                    }
                    '''
                }
            }
        }
        
        stage('推送镜像') {
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: 'docker-registry',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh """
                        docker login -u ${DOCKER_USER} -p ${DOCKER_PASS} ${DOCKER_REGISTRY}
                        docker push ${DOCKER_REGISTRY}/${APP_NAME}:${IMAGE_TAG}
                        docker push ${DOCKER_REGISTRY}/${APP_NAME}:latest
                        """
                    }
                }
            }
        }
        
        stage('蓝绿部署') {
            when {
                expression { params.DEPLOYMENT_TYPE == 'blue-green' }
            }
            steps {
                script {
                    // 确定当前活跃版本
                    sh '''
                    CURRENT_COLOR=$(kubectl get service ${APP_NAME}-service -n ${NAMESPACE} -o json 2>/dev/null | \
                        jq -r '.spec.selector.version // "blue"')
                    
                    if [ "$CURRENT_COLOR" = "blue" ]; then
                        NEW_COLOR="green"
                    else
                        NEW_COLOR="blue"
                    fi
                    
                    echo "🎨 当前版本: ${CURRENT_COLOR}, 新版本: ${NEW_COLOR}"
                    '''
                    
                    // 部署新版本
                    sh """
                    # 部署新版本
                    cat > ${NEW_COLOR}-deployment.yaml <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${APP_NAME}-${NEW_COLOR}
  namespace: ${NAMESPACE}
  labels:
    app: ${APP_NAME}
    version: ${NEW_COLOR}
spec:
  replicas: 2
  selector:
    matchLabels:
      app: ${APP_NAME}
      version: ${NEW_COLOR}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    metadata:
      labels:
        app: ${APP_NAME}
        version: ${NEW_COLOR}
    spec:
      containers:
      - name: ${APP_NAME}
        image: ${DOCKER_REGISTRY}/${APP_NAME}:${IMAGE_TAG}
        ports:
        - containerPort: 8080
        resources:
          requests:
            cpu: "100m"
            memory: "128Mi"
          limits:
            cpu: "200m"
            memory: "256Mi"
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
          successThreshold: 1
          failureThreshold: 3
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 3
          successThreshold: 1
          failureThreshold: 3
EOF
                    
                    kubectl apply -f ${NEW_COLOR}-deployment.yaml
                    
                    # 等待新版本就绪
                    kubectl rollout status deployment/${APP_NAME}-${NEW_COLOR} \
                        -n ${NAMESPACE} \
                        --timeout=${DEPLOYMENT_TIMEOUT}s || {
                        echo "❌ 新版本部署失败"
                        exit 1
                    }
                    """
                    
                    // 执行健康检查
                    sh """
                    ./scripts/health-check.sh ${NEW_COLOR} || {
                        echo "❌ 新版本健康检查失败"
                        exit 1
                    }
                    """
                    
                    // 切换流量
                    sh """
                    # 更新服务指向新版本
                    kubectl patch service ${APP_NAME}-service \
                        -n ${NAMESPACE} \
                        -p '{"spec":{"selector":{"version":"${NEW_COLOR}"}}}'
                    
                    # 验证流量切换
                    sleep 10
                    ACTUAL_VERSION=\$(kubectl get service ${APP_NAME}-service -n ${NAMESPACE} -o json | \
                        jq -r '.spec.selector.version')
                    
                    if [ "\${ACTUAL_VERSION}" != "${NEW_COLOR}" ]; then
                        echo "❌ 流量切换失败"
                        exit 1
                    fi
                    
                    echo "✅ 流量已切换到 ${NEW_COLOR} 版本"
                    """
                    
                    //



一、Jenkins蓝绿部署配置
1. Jenkins流水线配置 (Jenkinsfile-blue-green)

pipeline {
    agent any
    
    environment {
        DOCKER_REGISTRY = credentials('docker-registry')
        KUBECONFIG = credentials('kubeconfig')
        APP_NAME = 'demo-app'
        NAMESPACE = 'production'
        DEPLOYMENT_TIMEOUT = '300'
        HEALTH_CHECK_TIMEOUT = '120'
        ROLLBACK_ENABLED = 'true'
    }
    
    parameters {
        choice(
            name: 'DEPLOYMENT_TYPE',
            choices: ['blue-green', 'canary'],
            description: '选择部署策略'
        )
        string(
            name: 'IMAGE_TAG',
            defaultValue: "${BUILD_NUMBER}",
            description: '镜像标签'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: '是否执行干运行（仅验证不部署）'
        )
    }
    
    options {
        timeout(time: 30, unit: 'MINUTES')
        retry(2)
        disableConcurrentBuilds()
    }
    
    stages {
        stage('环境验证') {
            steps {
                script {
                    echo "🔍 开始环境验证..."
                    
                    // 验证Kubernetes连接
                    sh '''
                    if ! kubectl cluster-info; then
                        echo "❌ Kubernetes集群连接失败"
                        exit 1
                    fi
                    
                    # 验证集群版本
                    K8S_VERSION=$(kubectl version --short | grep Server | cut -d' ' -f3)
                    echo "✅ Kubernetes版本: ${K8S_VERSION}"
                    
                    # 验证命名空间
                    if ! kubectl get namespace ${NAMESPACE} &> /dev/null; then
                        echo "📦 创建命名空间 ${NAMESPACE}"
                        kubectl create namespace ${NAMESPACE}
                        
                        # 设置资源配额
                        cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ResourceQuota
metadata:
  name: production-quota
  namespace: ${NAMESPACE}
spec:
  hard:
    requests.cpu: "2"
    requests.memory: 4Gi
    limits.cpu: "4"
    limits.memory: 8Gi
    pods: "20"
EOF
                    fi
                    '''
                    
                    // 验证Docker仓库
                    sh '''
                    if ! docker login ${DOCKER_REGISTRY}; then
                        echo "❌ Docker仓库登录失败"
                        exit 1
                    fi
                    '''
                    
                    // 验证必要的Kubernetes资源
                    sh '''
                    # 检查默认存储类
                    if ! kubectl get storageclass | grep -q "(default)"; then
                        echo "⚠️  警告: 未找到默认存储类"
                    fi
                    '''
                }
            }
        }
        
        stage('代码检查与测试') {
            parallel {
                stage('代码质量检查') {
                    steps {
                        sh '''
                        echo "🔍 执行代码质量检查..."
                        
                        # 检查代码规范
                        if command -v eslint &> /dev/null; then
                            npx eslint . --ext .js,.jsx,.ts,.tsx || {
                                echo "⚠️  ESLint检查发现问题"
                            }
                        fi
                        
                        # 检查依赖漏洞
                        if command -v npm &> /dev/null; then
                            npm audit --audit-level=high || {
                                echo "⚠️  NPM依赖审计发现问题"
                            }
                        fi
                        '''
                    }
                }
                
                stage('单元测试') {
                    steps {
                        sh '''
                        echo "🧪 执行单元测试..."
                        
                        # 执行测试
                        if [ -f package.json ]; then
                            npm test -- --coverage || {
                                echo "❌ 单元测试失败"
                                exit 1
                            }
                            
                            # 生成测试报告
                            if [ -d coverage ]; then
                                cp -r coverage ${WORKSPACE}/test-reports/
                            fi
                        fi
                        '''
                    }
                    
                    post {
                        always {
                            junit '**/test-results/*.xml'
                            publishHTML([
                                reportDir: 'test-reports/coverage',
                                reportFiles: 'index.html',
                                reportName: '测试覆盖率报告'
                            ])
                        }
                    }
                }
                
                stage('安全扫描') {
                    steps {
                        script {
                            echo "🛡️ 执行安全扫描..."
                            
                            // 使用Trivy进行镜像漏洞扫描
                            sh '''
                            if command -v trivy &> /dev/null; then
                                trivy image --severity HIGH,CRITICAL \
                                    --exit-code 1 \
                                    ${DOCKER_REGISTRY}/${APP_NAME}:${IMAGE_TAG} || {
                                    echo "❌ 发现高危安全漏洞"
                                    if [ "${params.DRY_RUN}" != "true" ]; then
                                        exit 1
                                    fi
                                }
                            fi
                            '''
                        }
                    }
                }
            }
        }
        
        stage('构建Docker镜像') {
            steps {
                script {
                    echo "🐳 开始构建Docker镜像..."
                    
                    try {
                        // 检查Dockerfile是否存在
                        if (!fileExists('Dockerfile')) {
                            error("❌ Dockerfile不存在")
                        }
                        
                        // 构建镜像
                        sh """
                        docker build \
                            --build-arg BUILD_NUMBER=${BUILD_NUMBER} \
                            --build-arg COMMIT_SHA=${GIT_COMMIT} \
                            --build-arg BUILD_DATE=$(date -u +'%Y-%m-%dT%H:%M:%SZ') \
                            --tag ${DOCKER_REGISTRY}/${APP_NAME}:${IMAGE_TAG} \
                            --tag ${DOCKER_REGISTRY}/${APP_NAME}:latest \
                            --tag ${DOCKER_REGISTRY}/${APP_NAME}:${GIT_COMMIT} \
                            .
                        """
                        
                        // 验证镜像
                        sh """
                        docker inspect ${DOCKER_REGISTRY}/${APP_NAME}:${IMAGE_TAG} | \
                            jq -r '.[0].Config.Labels'
                        """
                        
                    } catch (Exception e) {
                        error("❌ 镜像构建失败: ${e.getMessage()}")
                    }
                }
            }
        }
        
        stage('推送镜像') {
            steps {
                script {
                    echo "📤 推送镜像到仓库..."
                    
                    withCredentials([usernamePassword(
                        credentialsId: 'docker-registry',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh """
                        docker login -u ${DOCKER_USER} -p ${DOCKER_PASS} ${DOCKER_REGISTRY}
                        
                        # 推送带标签的镜像
                        docker push ${DOCKER_REGISTRY}/${APP_NAME}:${IMAGE_TAG}
                        docker push ${DOCKER_REGISTRY}/${APP_NAME}:${GIT_COMMIT}
                        
                        # 仅在生产部署时推送latest标签
                        if [ "${params.DRY_RUN}" != "true" ]; then
                            docker push ${DOCKER_REGISTRY}/${APP_NAME}:latest
                        fi
                        """
                    }
                }
            }
        }
        
        stage('蓝绿部署') {
            when {
                expression { 
                    params.DEPLOYMENT_TYPE == 'blue-green' && 
                    params.DRY_RUN != 'true' 
                }
            }
            steps {
                script {
                    echo "🎨 开始蓝绿部署..."
                    
                    // 备份当前部署状态
                    sh '''
                    echo "📋 备份当前部署状态..."
                    kubectl get deployment -n ${NAMESPACE} -l app=${APP_NAME} -o yaml > ${WORKSPACE}/backup-deployments.yaml
                    kubectl get service -n ${NAMESPACE} ${APP_NAME}-service -o yaml > ${WORKSPACE}/backup-service.yaml
                    '''
                    
                    // 确定当前活跃版本
                    sh '''
                    CURRENT_COLOR="blue"
                    if kubectl get service ${APP_NAME}-service -n ${NAMESPACE} &> /dev/null; then
                        CURRENT_COLOR=$(kubectl get service ${APP_NAME}-service -n ${NAMESPACE} -o json | \
                            jq -r '.spec.selector.version // "blue"')
                    fi
                    
                    if [ "$CURRENT_COLOR" = "blue" ]; then
                        NEW_COLOR="green"
                    else
                        NEW_COLOR="blue"
                    fi
                    
                    echo "🎨 当前版本: ${CURRENT_COLOR}, 新版本: ${NEW_COLOR}"
                    '''
                    
                    // 部署新版本
                    sh """
                    echo "🚀 部署 ${NEW_COLOR} 版本..."
                    
                    # 创建新版本部署配置
                    cat > ${NEW_COLOR}-deployment.yaml <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${APP_NAME}-${NEW_COLOR}
  namespace: ${NAMESPACE}
  labels:
    app: ${APP_NAME}
    version: ${NEW_COLOR}
    environment: production
    build: ${BUILD_NUMBER}
    commit: ${GIT_COMMIT}
  annotations:
    deployment.timestamp: "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    deployment.strategy: "blue-green"
spec:
  replicas: 2
  selector:
    matchLabels:
      app: ${APP_NAME}
      version: ${NEW_COLOR}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    metadata:
      labels:
        app: ${APP_NAME}
        version: ${NEW_COLOR}
        environment: production
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/metrics"
    spec:
      containers:
      - name: ${APP_NAME}
        image: ${DOCKER_REGISTRY}/${APP_NAME}:${IMAGE_TAG}
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
          name: http
          protocol: TCP
        env:
        - name: APP_VERSION
          value: "${NEW_COLOR}-${IMAGE_TAG}"
        - name: NODE_ENV
          value: "production"
        resources:
          requests:
            cpu: "100m"
            memory: "128Mi"
          limits:
            cpu: "200m"
            memory: "256Mi"
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
            scheme: HTTP
          initialDelaySeconds: 15
          periodSeconds: 5
          timeoutSeconds: 3
          successThreshold: 1
          failureThreshold: 3
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
            scheme: HTTP
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 3
          successThreshold: 1
          failureThreshold: 3
        startupProbe:
          httpGet:
            path: /health
            port: 8080
            scheme: HTTP
          initialDelaySeconds: 5
          periodSeconds: 5
          timeoutSeconds: 3
          successThreshold: 1
          failureThreshold: 30
        securityContext:
          runAsNonRoot: true
          runAsUser: 1000
          allowPrivilegeEscalation: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchExpressions:
                - key: app
                  operator: In
                  values:
                  - ${APP_NAME}
              topologyKey: "kubernetes.io/hostname"
EOF
                    
                    # 应用部署
                    kubectl apply -f ${NEW_COLOR}-deployment.yaml
                    
                    # 等待新版本就绪
                    echo "⏳ 等待 ${NEW_COLOR} 版本就绪..."
                    kubectl rollout status deployment/${APP_NAME}-${NEW_COLOR} \
                        -n ${NAMESPACE} \
                        --timeout=${DEPLOYMENT_TIMEOUT}s || {
                        echo "❌ ${NEW_COLOR} 版本部署失败"
                        
                        # 自动回滚
                        if [ "${ROLLBACK_ENABLED}" = "true" ]; then
                            echo "🔄 执行自动回滚..."
                            kubectl rollout undo deployment/${APP_NAME}-${NEW_COLOR} -n ${NAMESPACE}
                            exit 1
                        fi
                    }
                    """
                    
                    // 执行健康检查
                    sh """
                    echo "🏥 执行健康检查..."
                    
                    # 等待Pod完全就绪
                    sleep 10
                    
                    # 获取新版本Pod IP
                    NEW_PODS=\$(kubectl get pods -n ${NAMESPACE} -l version=${NEW_COLOR} -o jsonpath='{.items[*].status.podIP}')
                    
                    for pod_ip in \${NEW_PODS}; do
                        echo "检查Pod: \${pod_ip}"
                        
                        # 检查就绪探针
                        for i in {1..10}; do
                            if curl -s -f -m 5 http://\${pod_ip}:8080/health > /dev/null; then
                                echo "✅ Pod \${pod_ip} 健康检查通过"
                                break
                            fi
                            
                            if [ \$i -eq 10 ]; then
                                echo "❌ Pod \${pod_ip} 健康检查失败"
                                exit 1
                            fi
                            
                            sleep 5
                        done
                    done
                    
                    # 检查服务端点
                    ENDPOINTS=\$(kubectl get endpoints ${APP_NAME}-service -n ${NAMESPACE} -o jsonpath='{.subsets[0].addresses[*].ip}')
                    if [ -z "\${ENDPOINTS}" ]; then
                        echo "❌ 服务端点为空"
                        exit 1
                    fi
                    
                    echo "✅ 所有健康检查通过"
                    """
                    
                    // 创建或更新服务
                    sh '''
                    echo "🔗 配置服务..."
                    
                    if ! kubectl get service ${APP_NAME}-service -n ${NAMESPACE} &> /dev/null; then
                        # 创建新服务
                        cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Service
metadata:
  name: ${APP_NAME}-service
  namespace: ${NAMESPACE}
  labels:
    app: ${APP_NAME}
spec:
  selector:
    app: ${APP_NAME}
    version: ${NEW_COLOR}
  ports:
  - port: 80
    targetPort: 8080
    protocol: TCP
    name: http
  type: ClusterIP
EOF
                    else
                        # 更新服务指向新版本
                        kubectl patch service ${APP_NAME}-service \
                            -n ${NAMESPACE} \
                            -p '{"spec":{"selector":{"version":"'${NEW_COLOR}'"}}}'
                    fi
                    '''
                    
                    // 验证流量切换
                    sh """
                    echo "🔄 验证流量切换..."
                    
                    # 等待服务更新
                    sleep 15
                    
                    # 验证服务选择器
                    ACTUAL_VERSION=\$(kubectl get service ${APP_NAME}-service -n ${NAMESPACE} -o json | \
                        jq -r '.spec.selector.version')
                    
                    if [ "\${ACTUAL_VERSION}" != "${NEW_COLOR}" ]; then
                        echo "❌ 流量切换失败，当前版本: \${ACTUAL_VERSION}"
                        exit 1
                    fi
                    
                    # 测试服务访问
                    SERVICE_IP=\$(kubectl get service ${APP_NAME}-service -n ${NAMESPACE} -o jsonpath='{.spec.clusterIP}')
                    
                    for i in {1..5}; do
                        if curl -s -f -m 10 http://\${SERVICE_IP}/health > /dev/null; then
                            echo "✅ 服务访问测试通过"
                            break
                        fi
                        
                        if [ \$i -eq 5 ]; then
                            echo "❌ 服务访问测试失败"
                            exit 1
                        fi
                        
                        sleep 3
                    done
                    
                    echo "✅ 流量已成功切换到 ${NEW_COLOR} 版本"
                    """
                    
                    // 清理旧版本（可选）
                    sh '''
                    echo "🧹 清理旧版本资源..."
                    
                    # 保留旧版本部署用于快速回滚
                    OLD_COLOR=""
                    if [ "${NEW_COLOR}" = "blue" ]; then
                        OLD_COLOR="green"
                    else
                        OLD_COLOR="blue"
                    fi
                    
                    # 缩小旧版本副本数为0（而不是删除）
                    kubectl scale deployment/${APP_NAME}-${OLD_COLOR} \
                        -n ${NAMESPACE} \
                        --replicas=0
                    
                    echo "📦 旧版本 ${OLD_COLOR} 已停止，保留部署用于回滚"
                    '''
                }
            }
        }
        
        stage('部署后验证') {
            when {
                expression { params.DRY_RUN != 'true' }
            }
            steps {
                script {
                    echo "✅ 部署后验证..."
                    
                    sh '''
                    # 验证所有Pod状态
                    kubectl get pods -n ${NAMESPACE} -l app=${APP_NAME} -o wide
                    
                    # 检查事件
                    kubectl get events -n ${NAMESPACE} \
                        --field-selector involvedObject.name=${APP_NAME}-${NEW_COLOR} \
                        --sort-by='.lastTimestamp' | tail -10
                    
                    # 检查HPA状态（如果配置了HPA）
                    if kubectl get hpa ${APP_NAME}-hpa -n ${NAMESPACE} &> /dev/null; then
                        kubectl get hpa ${APP_NAME}-hpa -n ${NAMESPACE}
                    fi
                    '''
                }
            }
        }
    }
    
    post {
        always {
            echo "📊 构建完成，状态: ${currentBuild.currentResult}"
            
            // 清理工作空间
            cleanWs()
        }
        
        success {
            script {
                echo "🎉 蓝绿部署成功完成！"
                
                // 发送成功通知
                em
