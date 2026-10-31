# 🎮 RMI Arena — Seminar Setup Guide

> **Algorithms for Distributed Systems · Seminar 1 · Java RMI**

---

## 🚨 TL;DR — What to do RIGHT NOW (Sunday)

- [ ] **Aditya:** Run `build.bat` → produces `rmi-arena.jar`
- [ ] **Everyone:** `java -version` → need **Java 17+** → https://adoptium.net
- [ ] **Aditya:** Copy `rmi-arena.jar` + `START-CLIENT.bat` to teammates (USB / Discord)
- [ ] **Test together on the uni WiFi** (or any shared WiFi) → run `ls` test below
- [ ] **Install Tailscale as a backup** (5 min, free) in case the WiFi has client isolation

---

## Plan A — University WiFi (should just work)

All 3 laptops connect to the same university WiFi → same network → RMI works directly.

**Aditya (server):**
```bat
java -jar rmi-arena.jar server
```
The banner prints your local IP, e.g. `192.168.x.x` or `10.x.x.x`. Give that to teammates.

**Teammates (clients):**
```bat
java -jar rmi-arena.jar client 192.168.x.x YourName
```
Or just double-click `START-CLIENT.bat` — it asks for the IP and name.

### Test it before Monday
From a teammate's laptop on the same WiFi:
```bat
java -jar rmi-arena.jar ls 192.168.x.x
```
- **Prints a file listing** → ✅ uni WiFi allows it, you're done, nothing else needed
- **"Cannot reach..."** → the WiFi has client isolation → use Plan B below

---

## Plan B — Phone Hotspot (instant backup, no setup)

If the WiFi blocks laptop-to-laptop traffic: one person turns on their phone hotspot,
everyone connects to it, RMI works exactly the same way.

| Phone | Where |
|---|---|
| iPhone | Settings → Personal Hotspot → toggle ON |
| Android | Settings → Connections → Mobile Hotspot → toggle ON |

Same commands as above — just connect to the hotspot WiFi instead of uni WiFi.

---

## Plan C — Tailscale VPN (works over any network, including internet)

Install once (Sunday), forget about it. If Plans A and B both fail on Monday,
Tailscale is the 60-second fix.

1. Everyone installs: https://tailscale.com/download (free, Google/GitHub login)
2. Aditya creates invite: https://login.tailscale.com/admin/invite → send link to teammates
3. Everyone clicks the invite link → now all on the same Tailscale network
4. Aditya's Tailscale IP (e.g. `100.x.x.x`) shows in the tray icon
5. Server: `java -jar rmi-arena.jar server --host 100.x.x.x`
6. Clients: connect to `100.x.x.x` instead of the LAN IP

---

## ⚡ Day-of checklist (Monday, 10 min before presenting)

```
□ All laptops on uni WiFi
□ START-SERVER.bat double-clicked → banner printed → IP noted
□ Both clients launched → game window open, player appears in arena
□ Press F3 in client → confirm "mode: PUSH" (callbacks working!)
□ Press TAB → file browser opens (remote ls works!)
□ Projector showing server Control Room with bots fighting → ready!
```

If clients can't connect → hotspot → re-run server → sorted in under 2 minutes.

---

One person turns on their phone hotspot. Everyone connects to it.  
Same network = RMI works directly.

| Phone | Steps |
|---|---|
| **iPhone** | Settings → Personal Hotspot → Allow Others to Join → toggle ON |
| **Android** | Settings → Connections → Mobile Hotspot → toggle ON |

Then start server normally — no `--host` flag needed:
```bat
java -jar rmi-arena.jar server
```
Note the IP from the banner and give it to teammates:
```bat
java -jar rmi-arena.jar client 192.168.x.x YourName
```

> ⚠️ **Don't rely on this as Plan A** — hotspot data might be limited, and it depends on mobile signal in the room.

---

## ⚡ Day-of checklist (Monday morning, 10 min before presenting)

```
□ Tailscale running on all 3 laptops (green icon in tray)
□ START-SERVER.bat double-clicked → banner printed → IP noted
□ Both clients launched → game window open, player appears in arena
□ Press F3 in client → confirm "mode: PUSH" (means callbacks working!)
□ Press TAB → file browser opens (confirm remote ls works)
□ Server dashboard visible (bots already fighting) → projector ready
```

If anything fails → **switch to phone hotspot immediately** (< 1 min).

---

## 🔧 Firewall (Windows — run once on each laptop)

Open terminal **as Administrator** and paste:
```bat
netsh advfirewall firewall add rule name="RMI Arena" dir=in action=allow protocol=TCP localport=1099-1101
```

When Java first opens a port, Windows pops up a firewall dialog.  
**Click "Allow" on both Private AND Public networks.**

---

## Controls

| Key | Action |
|---|---|
| `W`/`S` or `↑`/`↓` | Thrust |
| `A`/`D` or `←`/`→` | Turn |
| `Space` | **Fire laser** *(bounces off walls — bank shots do +65% damage!)* |
| `Shift` | **Dash** |
| `1`–`4` | Taunts |
| `Tab` | **Remote `ls`** — browse the server's folder via RMI |
| `F3` | RMI telemetry (shows PUSH/PULL mode, ping, bytes/frame) |
| `M` | Mute |

---

## Ports (for the presentation slides)

| Port | What |
|---|---|
| `1099` | RMI Registry |
| `1100` | Server remote objects (ArenaService, FileService) |
| `1101` | Client callbacks (ArenaListener — the push direction) |

---

## Troubleshooting

### "Cannot reach server:1099"
→ Tailscale not connected on one machine?  
→ Wrong IP? Copy it from the server banner exactly  
→ Firewall? Run the `netsh` command above  
→ **Switch to phone hotspot**

### Client shows `mode: PULL` instead of `PUSH`
This is **fine and actually a talking point!**  
The client's incoming firewall blocked callbacks — game auto-switches to polling.  
Press F3 and tell the audience: *"This is the push-vs-pull fallback we discussed."*

### Server shows the wrong IP (VMware/VirtualBox adapter)
```bat
java -jar rmi-arena.jar server --host 100.x.x.x
```
Use the Tailscale IP explicitly.

### "Registry answered but lookup failed"
Server didn't fully start. Restart it, wait for the banner to print.

### Nobody can connect and presentation starts in 2 minutes
Run bots from the server laptop itself — the demo still works perfectly:
```bat
java -jar rmi-arena.jar bot localhost 3
java -jar rmi-arena.jar client localhost Aditya
```
Audience can't tell the bots are local.

---

## Suggested 15-min demo script

| Time | What |
|---|---|
| `0:00` | Server running, bots fighting on projector — audience walks in |
| `1:00` | *"This whole game runs over Java RMI. Let me show you."* |
| `2:00` | Show the 3 HowTo files (DirectoryService, Server, Client) |
| `4:00` | Teammate runs `START-CLIENT.bat` live — Control Room log shows `join()` call |
| `5:30` | Both teammates in. Point at Control Room: PUSH mode, callback endpoint, calls/s |
| `7:00` | Live battle. Shoot a crate. Press TAB → remote `ls` → browse `secrets/` |
| `9:00` | Kill one client process → log shows *"timed out (8s)"* |
| `11:00` | Start client with `--pull` → F3 shows PULL mode → explain push vs pull |
| `13:00` | Verdict: zero IDL, callbacks easy; vs Java-only, serialisation cost, firewall pain |
| `15:00` | Questions |

---

## File structure (what to copy to teammates)

```
rmi-arena.jar       ← the game (teammates need THIS)
START-CLIENT.bat    ← double-click to join (teammates need THIS)
```

Teammates do NOT need the source code, build tools, or anything else.
