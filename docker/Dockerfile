# syntax=docker/dockerfile:1
# l2start-server: one image for db-init, login and game (stock Mobius + platform + modules).
# Build context = repository root:  docker build -f docker/Dockerfile .
#
# Mobius (core/build.xml) requires Java 27 and Ant >= 1.9.10.
# eclipse-temurin:27-jdk-alpine pinned by index digest (2026-10-03). Runtime also needs a full JDK:
# the game server compiles datapack scripts (core/dist/game/data/scripts) with javac at startup.
ARG JDK_IMAGE=eclipse-temurin:27-jdk-alpine@sha256:cd0c949d8a55b41cb6e7ac0fe464045aac42f97ab8032761b3843e52e68965de

# ---------- stage 1: build jars (core + platform) and modules with the ROOT build.xml ----------
FROM ${JDK_IMAGE} AS build
ARG ANT_VERSION=1.10.15
ARG ANT_SHA512=d78427aff207592c024ff1552dc04f7b57065a195c42d398fcffe7a0145e8d00cd46786f5aa52e77ab0fdf81334f065eb8011eecd2b48f7228e97ff4cb20d16c
RUN wget -q -O /tmp/ant.tgz "https://archive.apache.org/dist/ant/binaries/apache-ant-${ANT_VERSION}-bin.tar.gz" \
 && echo "${ANT_SHA512}  /tmp/ant.tgz" | sha512sum -c - \
 && tar xzf /tmp/ant.tgz -C /opt && rm /tmp/ant.tgz \
 && ln -s "/opt/apache-ant-${ANT_VERSION}/bin/ant" /usr/local/bin/ant
WORKDIR /src
COPY core/dist/libs ./core/dist/libs
COPY core/java ./core/java
COPY build.xml ./
COPY platform ./platform
COPY modules ./modules
# -> /src/build/dist/libs/{LoginServer,GameServer}.jar, /src/build/dist/game/{modules,config}, /src/build/fixtures
RUN ant -noinput dist fixtures

# ---------- stage 2: runtime (the image you run) ----------
FROM ${JDK_IMAGE} AS runtime
RUN apk add --no-cache mariadb-client tzdata \
 && addgroup -S l2 && adduser -S -G l2 -h /opt/l2 l2
ENV TZ=Europe/Moscow
WORKDIR /opt/l2
COPY core/dist/libs /opt/l2/libs
COPY core/dist/db_installer/sql /opt/l2/sql
COPY core/dist/login /opt/l2/login
COPY core/dist/game /opt/l2/game
COPY --from=build /src/build/dist/libs/LoginServer.jar /src/build/dist/libs/GameServer.jar /opt/l2/libs/
# Platform: modules/<id>/ and config/{platform.ini,modules.ini} (stock config files are not touched)
COPY --from=build /src/build/dist/game/modules /opt/l2/game/modules
COPY --from=build /src/build/dist/game/config /opt/l2/game/config
COPY docker/entrypoint.sh /entrypoint.sh
COPY docker/local-features.sh /local-features.sh
RUN sed -i 's/\r$//' /entrypoint.sh /local-features.sh && chmod 0755 /entrypoint.sh /local-features.sh \
 && rm -f /opt/l2/libs/*-sources.jar \
 && mkdir -p /opt/l2/login/log /opt/l2/game/log /opt/l2/overrides /opt/l2/game/config/modules \
 && chown -R l2:l2 /opt/l2
USER l2
EXPOSE 2106 7777
ENTRYPOINT ["/entrypoint.sh"]
CMD ["game"]

# ---------- stage 3: smoke (runtime + broken/odd test modules from platform/test/fixtures) ----------
# Only for tools/smoke.sh: docker build -f docker/Dockerfile --target smoke -t l2start-server:smoke .
FROM runtime AS smoke
COPY --from=build --chown=l2:l2 /src/build/fixtures /opt/l2/game/modules

# ---------- default target = runtime (a plain "docker build" never ships the fixtures) ----------
FROM runtime
