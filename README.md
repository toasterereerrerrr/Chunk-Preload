# Chunk Preloader

**A professional, high-performance chunk pre-generator for Minecraft (Fabric).**

Chunk Preloader eliminates "chunk-loading stutter" by pre-generating terrain before you start exploring. Whether you're a single-player adventurer or a server owner looking to optimize performance for dozens of players, this mod is designed to saturate your hardware and get the job done as fast as possible.

> ## WARNING: PERFORMANCE IMPACT
> Generating chunks is a heavy task for any CPU. **While preloading is active, you may experience lower TPS and occasional stutters.** 
>
> However, unlike basic pre-generators, this mod features **Smart Throttling**. It automatically monitors your server's TPS, RAM, and Disk space, pausing generation if the server becomes too busy. For those who want raw power, **Turbo Mode** is available to bypass all safety limits and generate at the absolute limit of your hardware.

---

## Key Features

*   **Extreme Speed**: Uses a fully asynchronous loading pipeline with "Immediate Refill" logic. It bypasses vanilla tick latency to generate chunks as fast as your CPU and SSD can handle.
*   **Custom Dimension Sequence**: Fully configurable dimension list. Preload any dimension (including modded ones) in the exact order you want.
*   **Pause and Resume**: Stop generation at any time to free up CPU for events or combat, then resume exactly where you left off. Progress is saved per-world.
*   **Detailed HUD Feedback**: The progress HUD now displays exact pause reasons (e.g., LOW RAM, BUSY TICK, PLAYERS ONLINE) so you know exactly why generation has throttled.
*   **Priority Points of Interest**: Define specific coordinates in the config to be generated first, ensuring your most important areas are ready immediately.
*   **Lighting Fix Mode**: A specialized mode that only runs the lighting engine. Perfect for fixing dark or black chunks in already generated areas.
*   **Dry Run Preview**: Use particles to visualize the corners of your pregen area before committing CPU time.
*   **Disk Usage Estimation**: Get an estimate of the final file size before starting large pregen runs.
*   **Completion Reports**: Play a sound and post a detailed summary (Time, Speed, Chunks) to console and Discord when a run finishes.
*   **Player Safety Bubble**: Automatically force-loads a small radius around online players in real-time, ensuring their local environment is always ready before they reach it.
*   **Universal Support (Server-Side)**: Only needs to be installed on the server. Clients joining without the mod can still play perfectly, while those with it get a beautiful real-time progress HUD.
*   **Smart and Dynamic Throttling**: 
    *   **TPS Protection**: Pauses if server TPS drops too low.
    *   **Memory Guard**: Aggressively flushes RAM and pauses if JVM usage is too high.
    *   **Auto-Turbo**: Automatically enables maximum speed when the server is empty and switches back to safety mode when players join.
*   **Flexible Areas**: Support for **Circular (Spiral)** and **Square** generation areas.
*   **Map Mod Sync**: Automatically triggers renders for **BlueMap**, **Dynmap**, and **Xaero's Map**.

---

## Commands

All commands require OP level 2.

| Command | Description |
|---|---|
| /chunkpreload start | Starts preloading around your current position. |
| /chunkpreload start <radius> | Starts preloading with a custom radius. |
| /chunkpreload start <radius> <x> <z> | Starts preloading around specific coordinates. |
| /chunkpreload pause | Temporarily halts generation without losing progress. |
| /chunkpreload resume | Continues a paused pregeneration. |
| /chunkpreload reset | Clears progress for the current dimension to re-run. |
| /chunkpreload border | Automatically preloads everything inside the world border. |
| /chunkpreload estimate | Shows estimated disk space usage for the current run. |
| /chunkpreload dryrun | Toggles Dry Run mode (shows area corners via particles). |
| /chunkpreload benchmark | Runs a 100-chunk hardware test to find your optimal speed. |
| /chunkpreload status | Shows progress, speed (ch/s), dimension, and ETA. |
| /chunkpreload stop | Immediately halts and resets all tasks. |
| /chunkpreload turbo | Toggles **Turbo Mode** (Bypasses all safety throttles). |

---

## Configuration

Reach the settings via **Mod Menu** or by binding a key to the **Chunk Preloader Config**.

*   **General**: Radius, Shape, CPU Presets (LOW to INSANE), and HUD Toggles.
*   **Advanced**: Custom Dimension List, Points of Interest, Player Safety Radius, Target Status, and Memory Flush toggles.
*   **Integration**: Discord Webhook URL and Map Mod sync settings.

---

## Requirements and Setup

*   **Minecraft**: 1.21.x (26.2)
*   **Loader**: Fabric
*   **Dependencies**: Fabric API, Cloth Config.

**Installation:** Just drop the .jar into your mods folder. For servers, no client installation is required!

---

## Good to know

*   **Background Generation**: Even when on the escape menu in a world, Chunk Preload will still continue to load the chunks in the background.
*   **Singleplayer Pause**: In singleplayer, opening the Escape menu freezes the world, which also pauses generation. Stay in the "Options" menu if you want preloading to continue.
*   **Target Status**: Defaults to minecraft:features. This generates terrain, trees, and ores at 2x speed compared to the full status used by traditional generators.

## License

This project is licensed under ARR (All Rights Reserved). All rights are reserved by the author and no redistribution, modification, or commercial use is permitted without explicit written permission.
