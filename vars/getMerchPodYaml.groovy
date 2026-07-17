def call() {
    return """
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: merch-build
spec:
  serviceAccountName: jenkins
  restartPolicy: Never
  containers:
  - name: devops
    image: 192.168.56.10:30082/merch-docker/devops-tools:latest
    command: ["sleep", "99d"]
    tty: true
    workingDir: /home/jenkins/agent
    volumeMounts:
    - name: build-cache
      mountPath: /home/jenkins/.npm
  - name: kaniko
    image: 192.168.56.10:30083/kaniko-project/executor:debug
    command: ["sleep", "99d"]
    tty: true
    workingDir: /home/jenkins/agent
    volumeMounts:
    - name: nexus-docker-config
      mountPath: /kaniko/.docker
  - name: security
    image: 192.168.56.10:30083/aquasec/trivy:latest
    command: ["sleep", "99d"]
    tty: true
    workingDir: /home/jenkins/agent
  volumes:
  - name: nexus-docker-config
    secret:
      secretName: nexus-docker-config
  - name: build-cache
    persistentVolumeClaim:
      claimName: jenkins-build-cache
"""
}
