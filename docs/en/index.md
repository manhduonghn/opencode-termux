# OpenCode for Termux — Documentation

This repository contains **OpenCode for Termux**, an Android app based on Termux that installs and
runs the official OpenCode CLI natively inside the Termux environment.

## Contents

- [Project overview and usage](../../README.md)
- [Building from source](../BUILDING.md)

## Notes

- Official OpenCode binaries are available for `arm64-v8a` and `x86_64` Android ABIs.
- Runtime installation, launcher management, and self-healing behavior are implemented by the app
  (see `app/src/main/java/com/termux/app/OpenCodeBootstrap.java`).
