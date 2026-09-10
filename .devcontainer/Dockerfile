FROM mcr.microsoft.com/devcontainers/java:3.0.0-17-jdk-bookworm

ARG ANDROID_SDK_ROOT=/opt/android-sdk
ARG CMDLINE_TOOLS_VERSION=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583

ENV ANDROID_HOME=${ANDROID_SDK_ROOT}
ENV ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT}
ENV PATH=${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${ANDROID_SDK_ROOT}/emulator:${PATH}

USER root

RUN rm -f /etc/apt/sources.list.d/yarn.list /etc/apt/sources.list.d/yarn.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates unzip wget \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -O /tmp/android-command-line-tools.zip \
    && echo "${CMDLINE_TOOLS_SHA256}  /tmp/android-command-line-tools.zip" | sha256sum -c - \
    && unzip -q /tmp/android-command-line-tools.zip -d /tmp/android-command-line-tools \
    && mv /tmp/android-command-line-tools/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm -rf /tmp/android-command-line-tools /tmp/android-command-line-tools.zip

RUN yes | sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" --licenses >/dev/null \
    && sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" \
        "platform-tools" \
        "platforms;android-37.0" \
        "build-tools;37.0.0" \
    && chown -R vscode:vscode "${ANDROID_SDK_ROOT}"

USER vscode
