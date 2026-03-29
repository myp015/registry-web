# ===============================
# 基于官方 Tomcat 9 + JDK 8
# 修复 Docker Registry 2.8+ SSL 验证问题
# ===============================

FROM tomcat:9-jdk8

# 设置 Tomcat 环境变量
ENV CATALINA_HOME=/usr/local/tomcat
ENV CATALINA_BASE=/usr/local/tomcat
ENV CATALINA_OPTS="-Djava.security.egd=file:/dev/./urandom -Djdk.tls.client.protocols=TLSv1.2 -Dhttps.protocols=TLSv1.2"
ENV PATH=$CATALINA_HOME/bin:$PATH

# 删除默认 webapps
RUN rm -rf $CATALINA_HOME/webapps/*

# 安装系统依赖、Java SSL 证书更新工具、Docker CLI（供 GC 调用 docker exec）
RUN apt-get update && \
    apt-get install -y ca-certificates-java libyaml-perl libfile-slurp-perl unzip wget docker.io && \
    update-ca-certificates -f && \
    rm -rf /var/lib/apt/lists/*

# 复制 Tomcat 配置及启动脚本
COPY tomcat/server.xml $CATALINA_BASE/conf/
COPY web-app/WEB-INF/config.yml /conf/config.yml
COPY tomcat/start.sh /usr/local/bin/start.sh
COPY tomcat/yml.pl /usr/local/bin/yml.pl

# 使用预编译 WAR（配合 .dockerignore）
COPY ROOT.war $CATALINA_HOME/webapps/
COPY application.properties $CATALINA_HOME/

WORKDIR $CATALINA_HOME
VOLUME /data
EXPOSE 8080

CMD ["/usr/local/bin/start.sh"]
