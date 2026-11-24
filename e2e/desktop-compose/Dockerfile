# The desktop e2e image: a JDK, Xvfb and the libraries Compose for Desktop (Skia) needs on Linux;
# fakeroot/dpkg-dev for packageDeb.  docker build -t zharp-desktop-e2e:jdk17 e2e/desktop-compose
FROM eclipse-temurin:17-jdk-jammy
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends xvfb xauth libxrender1 libxtst6 libxi6 libfontconfig1 libgl1 libglu1-mesa fakeroot binutils dpkg-dev && rm -rf /var/lib/apt/lists/*
