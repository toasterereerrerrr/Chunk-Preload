# RenderFast

**A professional, high-performance chunk pre-generator for Minecraft (Fabric).**

RenderFast eliminate "chunk-loading stutter" by pre-generating terrain before you start exploring. It features a fully asynchronous loading pipeline designed to saturate your hardware while maintaining strict server stability through aggressive watchdog protection and adaptive throttling.

> [!IMPORTANT]
> RenderFast now includes specialized support for **Voxy (Iris LOD)** and **Distant Horizons**, ensuring LOD data is correctly finalized and saved during pre-generation without overloading the async pipeline.

---

## 🚀 Key Features

*   **Extreme Speed**: Fully asynchronous generation using a throttled pipeline to prevent main-thread hangs.
*   **Watchdog Breather**: Aggressively yields to the server if a tick takes too long (Default: 5s limit), preventing the 60s Watchdog crash.
*   **Smart Throttling**: Real-time monitoring of TPS, RAM (Auto-GC), and Disk space.
*   **Multi-Dimension Sequencing**: Automatically moves from Overworld to Nether to End in a configurable order.
*   **LOD Mod Sync**: Native integration for **Voxy** and **Distant Horizons** to ensure smooth LOD generation.
*   **HUD Feedback**: Detailed progress bar with pause reasons (LOW RAM, BUSY TICK, LAG RECOVERY) and ETA.
*   **Priority POIs**: Generate specific coordinates or structures before the main spiral.
*   **Lighting Fix Mode**: A specialized light-only engine to fix dark/black chunks across existing worlds.

---

## ⌨️ Commands

All commands support the `/rf` alias and require OP level 2.

### 🎮 Control Commands
| Command | Description |
|---|---|
| `/rf start [r] [x] [z]` | Starts preloading. Radius and coordinates are optional. |
| `/rf status` | Shows detailed progress, speed (ch/s), and ETA. |
| `/rf pause` / `resume` | Temporarily halt or continue the current task. |
| `/rf stop` | Cancels the active task immediately. |
| `/rf reset` | Restarts the current task from 0% at your position. |
| `/rf turbo` | Toggles **Turbo Mode** (Bypasses safety throttles). |
| `/rf help` | Quick in-game reference for all commands. |

### 🛠️ System & Tools
| Command | Description |
|---|---|
| `/rf report <seconds>` | Frequency of progress updates in the server console. |
| `/rf webhook <url\|clear>` | Configure Discord alerts for task completion. |
| `/rf poi <add\|clear>` | Manage coordinates to generate before the main task. |
| `/rf oncomplete <cmd>` | Command to execute when a dimension finishes. |
| `/rf border` | Preloads everything inside the current world border. |
| `/rf dryrun` | Visualize the target area corners using particles. |
| `/rf estimate` | Estimates the final disk space usage for the run. |

### ⚙️ Detailed Configuration (`/rf config ...`)
| Sub-Command | Description |
|---|---|
| `enable` / `hud` / `dh` | Toggle global engine, HUD, or Distant Horizons sync. |
| `voxy` / `refill` / `gc` | Toggle Voxy sync, Immediate Refill, or Aggressive GC. |
| `radius <val>` | Set generation radius (1-2048). |
| `cpu <PROFILE>` | Presets: `LOW`, `MEDIUM`, `HIGH`, `VERY_HIGH`, `INSANE`. |
| `ram <%>` | RAM usage threshold (1-100) before auto-pausing. |
| `disk <MB>` | Minimum free disk space required to run. |
| `timeout <sec>` | Seconds before a "stuck" chunk is retried. |
| `status <status>` | Target generation depth (Default: `minecraft:features`). |

---

## ⚙️ Configuration Screen

Access the visual settings via **Mod Menu** or by clicking the **RF** button in the **Pause (ESC)** menu.

---

## 🛠️ Requirements

*   **Minecraft**: 1.21.x (26.2)
*   **Loader**: Fabric
*   **Dependencies**: Fabric API, Cloth Config.

---

## 💡 Pro Tip: Speed vs. Quality

*   **Status: features**: Generates terrain, trees, and ores. It is **2x faster** than the `full` status and is recommended for standard survival pre-generation.
*   **Turbo Mode**: Use only on empty servers. It ignores TPS and "Busy Tick" safety checks to maximize SSD write speed.

## 📄 License

This project is licensed under ARR (All Rights Reserved). No redistribution, modification, or commercial use is permitted without explicit written permission.
