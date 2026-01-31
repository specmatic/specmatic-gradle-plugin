FROM alpine:3.23

WORKDIR /usr/src/app

# Install OpenJDK 17, git, curl and any extra dependencies in a single RUN command
RUN apk upgrade && \
    apk add --no-cache openjdk17-jre git curl bash jq %EXTRA_APK_DEPS% && \
    rm -rf /var/cache/apk/*

SHELL ["/bin/bash", "-c"]

ADD %SOURCE_SBOM_PATH% %TARGET_SBOM_PATH%
ADD %SOURCE_JAR_PATH% %TARGET_JAR_PATH%
ADD %EXECUTABLE_NAME% /usr/local/bin/%EXECUTABLE_NAME%

%EXTRA_COMMANDS%

ENV SPECMATIC_EXECUTOR=docker

ENTRYPOINT ["/usr/local/bin/%EXECUTABLE_NAME%"]
