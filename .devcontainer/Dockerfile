# Pick a base that fits the project, e.g. mcr.microsoft.com/devcontainers/python:1-3.12-bookworm
FROM mcr.microsoft.com/devcontainers/python:1-3.12-bookworm

# --- Claude Code (native installer; auto-updates). Kept early so project layers below don't reinstall it.
USER vscode
RUN curl -fsSL https://claude.ai/install.sh | bash && mkdir -p /home/vscode/.claude
USER root
ENV PATH=/home/vscode/.local/bin:$PATH \
    CLAUDE_CONFIG_DIR=/home/vscode/.claude

# --- Project setup below (paths are relative to the project root).
# The web app (app/server.py) is standard-library Python; nothing to install.
EXPOSE 8420

# --- Android app toolchain (mobile/, Capacitor 8): Node 22, JDK 21, Android SDK 36.
# The base image's Yarn apt repo has an expired key and breaks apt-get update.
RUN rm -f /etc/apt/sources.list.d/yarn.list \
 && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
 && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" > /etc/apt/sources.list.d/adoptium.list \
 && apt-get update && apt-get install -y --no-install-recommends nodejs temurin-21-jdk unzip \
 && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk \
    JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp \
 && curl -fsSLo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
 && unzip -q clt.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && rm clt.zip \
 && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
 && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0" > /dev/null \
 && chown -R vscode:vscode $ANDROID_HOME
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
