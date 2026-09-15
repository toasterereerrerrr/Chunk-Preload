# Chunk Preloader

**A professional, high-performance chunk pre-generator for Minecraft (Fabric).**

Chunk Preloader eliminates "chunk-loading stutter" by pre-generating terrain before you start exploring. Whether you're a single-player adventurer or a server owner looking to optimize performance for dozens of players, this mod is designed to saturate your hardware and get the job done as fast as possible.

> ## ⚠️ PERFORMANCE WARNING
> Generating chunks is a heavy task for any CPU. **While preloading is active, you may experience lower TPS and occasional stutters.** 
>
> However, unlike basic pre-generators, this mod features **Smart Throttling**. It automatically monitors your server's TPS, RAM, and Disk space, pausing generation if the server becomes too busy. For those who want raw power, **Turbo Mode** is available to bypass all safety limits and generate at the absolute limit of your hardware.

---

## 🚀 Key Features

*   **⚡ Extreme Speed**: Uses a fully asynchronous loading pipeline with "Immediate Refill" logic. It bypasses vanilla tick latency to generate chunks as fast as your CPU and SSD can handle.
*   **🌌 Multi-Dimension Support**: Automatically sequences through dimensions. Finish the Overworld, then automatically move to the Nether and the End.
*   **🌍 Universal Support (Server-Side)**: Only needs to be installed on the server. Clients joining without the mod can still play perfectly, while those with it get a beautiful real-time progress HUD.
*   **🛡️ Smart Throttling**: The mod keeps your server stable by automatically pausing if:
    *   Server TPS drops too low.
    *   JVM Memory (RAM) usage exceeds a safe threshold (configurable).
    *   Free disk space is running low.
*   **🤖 Automation & Discord**: Set an **On-Complete Command** (like `/stop` or `/save-all`) and get **Discord Webhook** notifications the moment your pregen finishes.
*   **📐 Flexible Areas**: Choose between **Circular (Spiral)** generation for a natural feel or **Square** generation for perfect world-border alignment.
*   **🗺️ Map Mod Sync**: Automatically triggers renders for **BlueMap**, **Dynmap**, and **Xaero's Map** so your web maps are ready before players even join.

---

## 🛠️ Commands

All commands require OP level 2.

| Command | Description |
|---|---|
| `/chunkpreload start` | Starts preloading around your current position. |
| `/chunkpreload start <radius>` | Starts preloading with a custom radius. |
| `/chunkpreload start <radius> <x> <z>` | Starts preloading around specific coordinates. |
| `/chunkpreload border` | Automatically preloads everything inside your current world border. |
| `/chunkpreload benchmark` | Runs a 100-chunk hardware test to find your optimal speed. |
| `/chunkpreload status` | Shows detailed progress, speed (ch/s), dimension, and ETA. |
| `/chunkpreload stop` | Immediately halts all current tasks. |
| `/chunkpreload turbo` | Toggles **Turbo Mode** (Bypasses all safety throttles). |

---

## 📂 Configuration

Reach the settings via **Mod Menu** or by binding a key to the **Chunk Preloader Config** in your Controls menu.

*   **General**: Radius, Shape, CPU Usage Presets (`LOW` to `INSANE`), and HUD Toggles.
*   **Advanced**: Target Status (e.g., generate only `features` for 2x speed), TPS/Memory/Disk thresholds, and "Only When Empty" mode.
*   **Integration**: Discord Webhook URL and Map Mod sync toggles.

---

## 📋 Requirements & Setup

*   **Minecraft**: 1.21.x (26.2)
*   **Loader**: Fabric
*   **Dependencies**: Fabric API, Cloth Config.
*   **Optional**: Mod Menu (for easier config access).

**Installation:** Just drop the `.jar` into your `mods` folder. For servers, no client installation is required!

---

## 💡 Good to know

*   **Singleplayer Pause**: In singleplayer, opening the Escape menu freezes the world, which also pauses generation. Stay in the "Options" menu if you want preloading to continue while you're away.
*   **Resumable**: Progress is saved per-world. If the server crashes or you close the game, it will pick up exactly where it left off on next launch.

## License

This project is licensed under ARR (All Rights Reserved). All rights are reserved by the author and no redistribution, modification, or commercial use is permitted without explicit written permission.
