FROM alpine:3.22

WORKDIR /usr/src/app

# Install OpenJDK 17, git, and curl in a single RUN command
RUN apk add --no-cache openjdk17-jre git curl bash && \
    rm -rf /var/cache/apk/*

SHELL ["/bin/bash", "-c"]

ADD %SOURCE_JAR_PATH% %TARGET_JAR_PATH%
ADD %IMAGE_NAME% /usr/local/bin/%IMAGE_NAME%

ENTRYPOINT ["/usr/local/bin/%IMAGE_NAME%"]
