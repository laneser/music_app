# Android build + test environment for CelloCoach.
# Provides JDK 17, Android SDK (platform 34, build-tools, platform-tools),
# the emulator + a system image (KVM-accelerated), Gradle, and adb/usb tooling.
FROM eclipse-temurin:17-jdk-jammy

ARG DEBIAN_FRONTEND=noninteractive

# --- OS packages -----------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip git ca-certificates \
        # adb + usb passthrough for deploying to a physical phone
        android-tools-adb usbutils \
        # emulator runtime deps (headless software/GPU paths)
        libpulse0 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 \
        libxtst6 libxrandr2 libasound2 libglu1-mesa libgl1 \
        qemu-kvm \
    && rm -rf /var/lib/apt/lists/*

# --- Android SDK -----------------------------------------------------------
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=${ANDROID_SDK_ROOT}
ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${ANDROID_SDK_ROOT}/emulator

ARG CMDLINE_TOOLS_VERSION=11076708
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/cmdtools.zip \
    && unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools \
    && mv /tmp/cmdtools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
    && rm -rf /tmp/cmdtools.zip /tmp/cmdtools

RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-34" \
        "build-tools;34.0.0" \
        "emulator" \
        "system-images;android-34;google_apis;x86_64" >/dev/null

# --- Gradle ----------------------------------------------------------------
ARG GRADLE_VERSION=8.7
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle \
    && rm /tmp/gradle.zip

# Pre-create a Gradle home so caches persist via the mounted volume.
ENV GRADLE_USER_HOME=/home/vscode/.gradle

# Non-root dev user (matches devcontainer "remoteUser")
RUN useradd -m -s /bin/bash vscode \
    && mkdir -p ${GRADLE_USER_HOME} \
    && chown -R vscode:vscode ${GRADLE_USER_HOME} ${ANDROID_SDK_ROOT}

USER vscode
WORKDIR /workspace
