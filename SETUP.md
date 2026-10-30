# 🎮 RMI Arena — Seminar Setup Guide

> **Algorithms for Distributed Systems · Seminar 1 · Java RMI**  
> Framework: **Java RMI** | Demo: Multiplayer laser battle over RMI

---

## Quick Summary

| Role | Laptop | What to run |
|---|---|---|
| **Server** | Aditya's laptop | `java -jar rmi-arena.jar server` |
| **Client 1** | Teammate 1 | `java -jar rmi-arena.jar client <server-ip> <YourName>` |
| **Client 2** | Teammate 2 | `java -jar rmi-arena.jar client <server-ip> <YourName>` |

---

## Step 0 — Before the day (do this the day before!)

### Everyone: Check Java

Open a terminal and run:
```
java -version
```
You need **Java 17 or newer**. If you don't have it:
- Windows: download from https://adoptium.net (Temurin 21, installer)
- Or ask Aditya to copy the JRE onto a USB

### Aditya (Server): Build the jar

In the project folder, double-click **`build.bat`** or open a terminal there and run:
```bat
build.bat
```

This creates `rmi-arena.jar`. **Copy this one file to both teammates** (USB, WhatsApp, Discord).

> The teammates only need the `.jar`. They do NOT need source code or a JDK — just a JRE.

---

## Step 1 — Network Setup (CRITICAL — do this first!)

### Option A: Phone Hotspot (MOST RELIABLE)

1. **One person** turns on their phone hotspot
2. **All three laptops** connect to this hotspot
3. Done. Move on.

> **Eduroam / university Wi-Fi blocks laptop-to-laptop traffic!**
> The game will NOT work on eduroam. Use a phone hotspot.

### Option B: Tailscale VPN (also works)

1. Everyone installs Tailscale from https://tailscale.com
2. Everyone logs in and joins the same network
3. Start the server with `--host <your-tailscale-ip>` (e.g. `100.x.x.x`)

---

## Step 2 — Firewall (Windows only, run once)

On **all three laptops**, open a terminal **as Administrator** and run:

```bat
netsh advfirewall firewall add rule name="RMI Arena" dir=in action=allow protocol=TCP localport=1099-1101
```

> When Java first tries to listen on a port, Windows will pop up a firewall dialog.
> Click **"Allow access" on BOTH "Private" and "Public" networks**.

To clean up after the seminar:
```bat
netsh advfirewall firewall delete rule name="RMI Arena"
```

---

## Step 3 — Start the Server (Aditya's laptop)

Open a terminal in the project folder and run:

```bat
java -jar rmi-arena.jar server
```

The server will print a banner like this:

```
========================================================================
  RMI ARENA server is running

  Registry       rmi://192.168.1.42:1099
  Bound names    ArenaService, FileService
  Object port    1100

  Friends join with:   java -jar rmi-arena.jar client 192.168.1.42 <name>
========================================================================
```

**Note the IP address** (e.g. `192.168.1.42`) — teammates need it.

> If the wrong IP is shown (e.g. a VMware adapter):
> `java -jar rmi-arena.jar server --host 192.168.1.42`

---

## Step 4 — Join as Clients (Teammates' laptops)

Each teammate runs this (replace IP and name):

```bat
java -jar rmi-arena.jar client 192.168.1.42 Alice
java -jar rmi-arena.jar client 192.168.1.42 Bob
```

A login dialog pops up if no args are given — just fill in the IP and name.

> To also play on the server laptop, open a second terminal:
> `java -jar rmi-arena.jar client localhost Aditya`

---

## Step 5 — Add Bots (if someone can't connect, or just for chaos)

On any laptop (or the server):

```bat
java -jar rmi-arena.jar bot 192.168.1.42 2
```

This adds 2 AI players. You can also click **"🤖 + Bot"** in the server Control Room window.

---

## Controls

| Key | Action |
|---|---|
| `W`/`S` or arrows | Thrust forward / back |
| `A`/`D` | Turn |
| `Space` | **Fire laser** (bank shots = +65% damage!) |
| `Shift` | **Dash** |
| `1`–`4` | Taunts (`java.rmi.RemoteException: skill not found`) |
| `Tab` | **Remote `ls`** — browse server's shared/ folder live |
| `F3` | RMI telemetry overlay (ping, push/pull mode, bytes/s) |
| `M` | Mute / unmute |

---

## Ports Used (for the slides)

| Port | What |
|---|---|
| **1099** | RMI Registry (`LocateRegistry.createRegistry(1099)`) |
| **1100** | Server remote objects (`ArenaService`, `FileService`) |
| **1101** | Client callback listeners (`ArenaListener`) |

---

## Troubleshooting

### "Cannot reach server-ip:1099"
- Wrong IP? Check the server banner
- Not on the same hotspot? Reconnect
- Firewall? Run the `netsh` command above on the **server** laptop
- If all else fails, run bots from the server and demo with localhost

### "Registry answered but lookup failed"
- Did the server fully start? Look for the banner in the terminal
- Restart the server and try again

### Client shows PULL mode instead of PUSH
This is **expected and actually demo-worthy!** The client's firewall is blocking
incoming callbacks. The game still works — client polls instead of receiving pushes.
Press F3 in the client and point at the mode: "This is the push-vs-pull fallback."

### Test connectivity before the seminar
From a teammate's laptop:
```bat
java -jar rmi-arena.jar ls 192.168.1.42
```
If it prints a directory listing → everything works.

---

## What the Audience Sees During the Demo

1. **Server window** — "Control Room" on the projector:
   - Left: live arena view (spectator)
   - Right: connected clients, calls/second, live RMI call log
2. **Client windows** — each teammate's laptop shows their player
3. Press **F3** in any client to reveal: push/pull mode, callback endpoint, ping RTT
4. Press **Tab** to open the remote file browser (live RMI `ls` calls, visible in the log)
5. **Shoot crates** to destroy files from the shared folder
6. Try browsing into `secrets/` — the audience will love it

---

## Suggested Demo Script (15 minutes)

| Time | Action |
|---|---|
| 0:00 | Server running, bots fighting — audience walks in to action |
| 1:00 | "This entire game runs over Java RMI. Here's how." |
| 2:00 | Show the 3 HowTo files (DirectoryService, Server, Client) |
| 4:00 | Live `ls` from a teammate's terminal — Control Room log shows the call |
| 5:00 | Teammates join. Show Control Room: PUSH mode, callback endpoint |
| 7:00 | Battle. Point at calls/s counter. Shoot a crate. Press Tab → remote ls |
| 9:00 | Kill a client process → "timed out (8s)" appears in the log |
| 11:00 | Start a client with `--pull` → F3 shows PULL mode — explain the fallback |
| 13:00 | Verdict: strengths/weaknesses vs. gRPC |
| 15:00 | Q&A |

---

*Good luck! If all else fails, bots + localhost is still a solid demo.*
