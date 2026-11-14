# 1단계: Spring 앱 빌드
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

# 2단계: mysqld-debug + GDB + Spring을 한 컨테이너에
# 아래 RUN 명령은 v0.8 tracer/Dockerfile과 같아서, 받아 둔 디버그 패키지 레이어가 캐시로 재사용된다.
FROM docker.io/library/mysql:8.4.6
RUN microdnf install -y gdb python3 cpio rpm && microdnf clean all
RUN curl -fL --retry 3 -o /tmp/server-debug.rpm \
      https://repo.mysql.com/yum/mysql-8.4-community/el/9/x86_64/mysql-community-server-debug-8.4.6-1.el9.x86_64.rpm \
    && curl -fL --retry 3 -o /tmp/server-symbols.rpm \
      https://repo.mysql.com/yum/mysql-8.4-community/el/9/debuginfo/x86_64/mysql-community-server-debug-debuginfo-8.4.6-1.el9.x86_64.rpm \
    && cd / && rpm2cpio /tmp/server-debug.rpm | cpio -idm \
    && cd / && rpm2cpio /tmp/server-symbols.rpm | cpio -idm \
    && rm -f /tmp/server-debug.rpm /tmp/server-symbols.rpm \
    && mkdir -p /run/mysqld /run/locklab \
    && chown mysql:mysql /run/mysqld
RUN curl -fL --retry 3 -o /tmp/common-symbols.rpm \
      https://repo.mysql.com/yum/mysql-8.4-community/el/9/debuginfo/x86_64/mysql-community-debuginfo-8.4.6-1.el9.x86_64.rpm \
    && cd / && rpm2cpio /tmp/common-symbols.rpm | cpio -idm \
    && rm -f /tmp/common-symbols.rpm
RUN microdnf install -y numactl-libs && microdnf clean all

# JRE는 Temurin 이미지에서 복사한다
COPY --from=docker.io/library/eclipse-temurin:21-jre /opt/java/openjdk /opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:$PATH

COPY tracer /opt/tracer
COPY entrypoint.sh /entrypoint.sh
COPY --from=build /workspace/target/app.jar /app/app.jar

# 빌드 시점 확인: 디버그 심볼에 프로브 대상 함수가 있는가, 순수 함수 테스트 통과
RUN gdb --batch --nx /usr/sbin/mysqld-debug -ex 'info address lock_rec_lock' | grep 'is a function' \
    && python3 -m unittest discover -s /opt/tracer/tests \
    && chmod +x /entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
