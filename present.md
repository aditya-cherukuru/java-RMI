Error: API error (attempt 1): request failed: Post "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse": dial tcp: lookup daily-cloudcode-pa.googleapis.com: no such host (Error ID: a2ca1b85-c5d1-4d5b-8755-b91fc3dd3a62-223)

Error: API error (attempt 2): request failed: Post "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse": dial tcp: lookup daily-cloudcode-pa.googleapis.com: no such host (Error ID: a2ca1b85-c5d1-4d5b-8755-b91fc3dd3a62-224)

Error: API error (attempt 3): request failed: Post "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse": dial tcp: lookup daily-cloudcode-pa.googleapis.com: no such host (Error ID: a2ca1b85-c5d1-4d5b-8755-b91fc3dd3a62-225)

Ran inspect MapBuilder.java
Analyzed MapBuilder.java:L51-85
Analyzed ArenaServiceImpl.java:L1-60
Analyzed PlayerInput.java:L1-8
Analyzed Frame.java:L1-11
Analyzed GameWorld.java:L1-60
Analyzed ArenaPanel.java:L245-280

Here is your complete **Master Guide**. It contains the hidden distributed systems details buried in your code, the exact tab-by-tab screenplay, the 5 hardest questions the professor could ask (with winning answers), and your final closing statement.

---

### Part 1: The 6 "Hidden Gems" in Your Code (That You Have Right Now)

Your codebase contains several advanced distributed systems design patterns. Mentioning these will prove to the professor that you understand real engineering:

| # | Hidden Feature | Where in Code | Why it Matters (Say This to the Class) |
|---|---|---|---|
| **1** | **Dynamic Map Generation** | [`MapBuilder.java`](file:///c:/Users/Aditya/Desktop/7%20th%20sem%20THWS/Algo%20for%20DIstributed%20Sysytem/Presentation/src/arena/server/MapBuilder.java#L72-L75) (lines 72–75) | Subdirectories become mirrors. Files become crates. **A crate's size and HP are calculated from the file's byte size on disk** (`log10(size)`). Big files take more laser hits to destroy! |
| **2** | **Path Traversal Jail Security** | [`FileServiceImpl.java`](file:///c:/Users/Aditya/Desktop/7%20th%20sem%20THWS/Algo%20for%20DIstributed%20Sysytem/Presentation/src/arena/server/FileServiceImpl.java#L32) (line 32) | Prevents directory traversal attacks (`../../`). If a malicious client tries to list `C:\Windows` or private files, the server throws an access-denied `RemoteException`. |
| **3** | **Snapshot Conflation** | [`Session.java`](file:///c:/Users/Aditya/Desktop/7%20th%20sem%20THWS/Algo%20for%20DIstributed%20Sysytem/Presentation/src/arena/server/Session.java#L112) (line 112) | If a client has network jitter, the server **discards stale intermediate frames** using `pending.set(newSnapshot)`. It never buffers 50 old frames, preventing memory leaks and lag spikes. |
| **4** | **Queueing Events vs. Conflating State** | [`Frame.java`](file:///c:/Users/Aditya/Desktop/7%20th%20sem%20THWS/Algo%20for%20DIstributed%20Sysytem/Presentation/src/arena/common/Frame.java#L10) (line 10) | Game positions are conflated (only latest matters), **but events (kills, sounds, chat) are strictly queued in an outbox** so players never miss a kill message or sound effect! |
| **5** | **Idempotent Input Keys** | [`PlayerInput.java`](file:///c:/Users/Aditya/Desktop/7%20th%20sem%20THWS/Algo%20for%20DIstributed%20Sysytem/Presentation/src/arena/common/PlayerInput.java#L5) (line 5) | Clients send raw boolean key states (`forward`, `fire`), not velocity deltas. If a packet drops or arrives twice, the server's physics remain consistent. |
| **6** | **Session Tokens Against Spoofing** | [`ArenaServiceImpl.java`](file:///c:/Users/Aditya/Desktop/7%20th%20sem%20THWS/Algo%20for%20DIstributed%20Sysytem/Presentation/src/arena/server/ArenaServiceImpl.java#L45) (line 45) | On join, the server issues a cryptographically secure 64-bit random token. Every call requires this token, preventing one player from controlling another player's ship. |

---

### Part 2: The Exact File-to-Game Screenplay

Have these **5 tabs** open in your IDE before your part starts:

```
[Tab 1: FileService.java] → [Tab 2: ArenaService.java] → [Tab 3: ArenaListener.java] → [Tab 4: ArenaClient.java] → [Tab 5: Session.java]
```

#### Step 1: In the IDE (2.5 mins)

1. **Click Tab 1: `FileService.java`** (Point at line 12: `List<FileEntry> list(...)`)
   > *"We start with `FileService`. It is our remote contract for directory listing. Notice on line 12 that it returns a serializable list of `FileEntry` objects containing metadata—names, sizes, and directory flags. In `FileServiceImpl`, we strictly jail clients to the shared folder to block `../` path traversal attacks."*

2. **Click Tab 2: `ArenaService.java`** (Point at line 20: `join(...)` & line 23: `sendInput(...)`)
   > *"Next is the game server interface. `join()` takes a player name and an `ArenaListener` callback stub. Notice line 23: `sendInput()`. It transmits the player's key states and acts as our heartbeat lease."*

3. **Click Tab 3: `ArenaListener.java`** (Point at line 12: `void onFrame(...)`)
   > *"Here is the callback contract: `ArenaListener`. It has just this one method: `onFrame()`. In standard RPC, clients pull data. But for event-based communication, this one interface method allows the server to turn RPC around and push state directly to every laptop."*

4. **Click Tab 4: `ArenaClient.java`** (Point at line 148: `Net.exportCallback()` & line 160: `arena.join(...)`)
   > *"Here on the client side: on line 148, the client exports its local listener on port 1101. On line 160, it calls `arena.join(name, stub)`. We are passing our own remote stub across the network as a method parameter!"*

5. **Click Tab 5: `Session.java`** (Point at line 58: `pusher` thread & line 112: `pending.set(...)`)
   > *"Finally, on the server: line 58 assigns a dedicated pusher thread per connected laptop. If one player's Wi-Fi stutters, their thread handles it without stalling the 30 Hz server game loop. And on line 112, we implement **Snapshot Conflation**—dropping stale frames so slow clients never build up lag."*

---

#### Step 2: Launching Server & Client Connection (3 mins)

1. **Minimize IDE → Double-click `START-SERVER.bat`**.
2. **Move the Server Control Room window onto the projector.**
   > *"Now let's launch the server. The Control Room is our projector spectator view. It created an in-process RMI registry on port 1099, exported the remote services on port 5000, and initialized the telemetry tracker."*

3. **Teammates 1 & 2 run `START-CLIENT.bat` on their laptops.**
   *(Watch the ships appear in the Control Room)*
   > *"Watch the dashboard:*  
   > * * Both teammates just executed `arena.join()`.*  
   > * * The server assigned them ship colors and spawned them instantly on screen.*  
   > * * In the client table, you can see their IPs, mode **PUSH**, and callback latency running at **1 to 2 milliseconds**!"*

---

#### Step 3: In-Game Dogfight & Remote `ls` (2 mins)

1. **Play for 45 seconds:** Fly, shoot lasers, bounce beams off mirrors.
   > *"The simulation is fully server-authoritative. Clients only send key presses. The server computes all velocities, collisions, and reflections on its 30 Hz loop, then broadcasts the state back to both laptops."*

2. **In your client window, press <kbd>TAB</kbd>:**
   > *"Now, look at the obstacles in the arena. They were generated directly by calling `FileService.list()` on the server's folder! Subdirectories became laser-reflecting mirrors, and files became crates with hitpoints proportional to their file size.*  
   > *When I press <kbd>TAB</kbd>, here is the remote file browser querying the server live over RMI."*

3. **Shoot a crate:**
   > *"Notice the kill feed at the top right: destroying a crate logs the file name: `Player ran rm homework_FINAL.txt`."*

---

#### Step 4: Disconnect Test (1.5 mins)

1. **Ask Teammate 2 to close their client window.**
2. **Point at the Control Room table:**
   > *"In distributed systems, nodes die silently. Every time a client sends keyboard input via `sendInput()`, it acts as a heartbeat lease.*  
   > *Watch the dashboard: my teammate just closed their client. The server stops receiving inputs. After an 8-second lease timeout, the server automatically cleans up the remote session, removes their ship, and logs the disconnect."*

---

### Part 3: The Professor Defense Guide (5 Hard Questions & Winning Answers)

#### Q1: *"Why did you use Callbacks instead of having clients Poll the server?"*
> **Answer:**  
> *"Polling introduces a trade-off between latency and bandwidth. If clients poll every 10 ms, they waste network resources sending empty requests. If they poll every 200 ms, the game feels unplayable. Callbacks invert the control flow: the client sits completely idle, and the server pushes updates the instant a game tick completes, giving us 1 ms responsiveness with zero wasted polling calls."*

#### Q2: *"What happens if one laptop is on a terrible Wi-Fi connection? Does the whole game freeze?"*
> **Answer:**  
> *"No, because of two architectural layers in `Session.java`:*  
> *1. **Thread-per-client isolation:** Each player has a dedicated single-threaded executor (`pusher`). A slow TCP handshake on one laptop never blocks the server's main 30 Hz tick loop.*  
> *2. **Snapshot Conflation:** If a laptop falls behind, we overwrite `pending.set(newSnapshot)` and drop the intermediate frames. When their connection recovers, they immediately receive the latest state rather than replaying old history."*

#### Q3: *"Can a client cheat by claiming they shot another player?"*
> **Answer:**  
> *"No. The client has zero authority over game physics. The client's `sendInput()` call only transmits boolean key states—whether the player is steering or holding the fire button. Ray-tracing, laser trajectories, bounding-box collisions, and health point subtractions are calculated entirely inside `GameWorld.java` on the server."*

#### Q4: *"Why do we need a secret token in `sendInput()`?"*
> **Answer:**  
> *"RMI stubs are shared interfaces. If `sendInput()` only took a player ID like `sendInput(int playerId, input)`, a malicious client could invoke `sendInput(2, ...)` and hijack another student's ship. On `join()`, the server generates a cryptographically secure 64-bit random token. Every subsequent call validates this token against the active session map."*

#### Q5: *"Would you use Java RMI for a commercial multiplayer game today?"*
> **Answer:**  
> *"No. For 3 to 10 nodes on a local network, RMI is remarkably productive. But for internet-scale games, RMI suffers from two fatal flaws: first, Java serialization is heavy (~1.1 KB per frame vs. ~150 bytes in binary formats like Protobuf). Second, two-way callbacks require opening inbound ports on client machines, which consumer NATs and firewalls block. Commercial games use UDP with custom delta-compression, or multiplexed streams over HTTP/2 (gRPC)."*

---

### Part 4: The Ultimate Closing Statement (Word-for-Word)

*(Switch back to Slide 12: THANK YOU, face the class, and deliver this closing)*

> **What to Say:**  
> *"To bring everything together: why did we use Java RMI here, how did it help us, and where does it stand today?*  
>  
> ***How Java RMI helped us:**  
> *It gave us complete distributed object transparency. We didn't need to define IDL schemas, compile protocol buffers, or write JSON parsers. In pure Java, we wrote normal interfaces, passed remote stubs by reference as method arguments, and implemented two-way callbacks in just a few lines of code.*  
>  
> ***Where it reaches its limits:**  
> *Standard Java object serialization carries class metadata overhead that consumes significant bandwidth at high frequencies. Furthermore, bi-directional callbacks require open incoming ports on client machines, which makes RMI difficult across modern firewalls and NATs.*  
>  
> ***Final Verdict:**  
> *Java RMI is a brilliant, elegant framework for building tightly coupled distributed systems across trusted clusters, but modern industry has evolved toward HTTP/2 multiplexing and cross-language protocols like gRPC.*  
>  
> *Thank you very much for your time, and we're happy to answer any questions!"*





**YES, EXACTLY! You just hit the nail on the head.** 

That is the entire definition of **Location Transparency** in distributed systems.

In terms of pure Java, your entire distributed system is just **3 pieces**:

---

### 1. The Interfaces (The Contracts)
*These live in `src/arena/common/` and just `extend Remote`:*
* **`FileService`** — *"Here is how to list files (`list()`)"*
* **`ArenaService`** — *"Here is how clients talk to the server (`join()`, `sendInput()`)"*
* **`ArenaListener`** — *"Here is how the server talks back to clients (`onFrame()`)"*

They have zero implementation code—just normal Java method signatures declaring `throws RemoteException`.

---

### 2. The Implementations (The Real Work)
*These are standard Java classes that implement those interfaces:*
* **`FileServiceImpl`** implements `FileService` (reads the local hard drive).
* **`ArenaServiceImpl`** implements `ArenaService` (manages player tokens and sessions).
* **`CallbackListener`** (inside `ArenaClient.java`) implements `ArenaListener` (takes the frame and paints it on the screen).

---

### 3. The Main Files (The Orchestrators)
* **`ArenaServer.java`** — Starts up, calls `UnicastRemoteObject.exportObject()`, and registers the implementation under `"ArenaService"` in the RMI Registry.
* **`ArenaClient.java`** — Looks up `"ArenaService"` from the registry, gets the stub, and calls `arena.join()` just like calling any ordinary Java object method.
* **`Session.java`** — Takes the client's `ArenaListener` stub and calls `listener.onFrame()` at 30 Hz.

---

### Why this is a great thing to say to the professor:

You can literally say this in your presentation:

> *"If you look at our code in terms of Java, it is just normal Java interfaces and normal classes implementing them.*  
>  
> *We didn't write any raw TCP socket handling, we didn't define byte buffers, and we didn't write JSON parsers.*  
>  
> *Java RMI gives us complete **Location Transparency**: we write standard Java interfaces, and the Java runtime automatically generates the dynamic proxy stubs that serialize the method calls and dispatches them across the network."*

It sounds simple because Java RMI was specifically designed in 1996 to make distributed programming feel identical to standard local Java programming!






Ran check if files are actually deleted on disk

Here is the exact breakdown of how data travels and where it lives:

---

### 1. How Server and Client Share Data: Java Serialization

Under the hood, Java RMI uses **two mechanisms** to share data across the network:

#### A. Pass-by-Value (Copying Data Objects)
Any class that implements `java.io.Serializable` (like `Frame`, `PlayerInput`, `FileEntry`, `GameSnapshot`) is shared **by copy**:
1. When your teammate presses <kbd>W</kbd>, their laptop takes the `PlayerInput` object in RAM and **marshals** (serializes) it into a stream of binary bytes.
2. It sends those bytes over the TCP socket to your laptop.
3. Your server **unmarshals** (deserializes) those bytes into a brand-new Java object in your server's RAM.
* *The client and server don't share the same memory; they send deep copies of objects over the wire.*

#### B. Pass-by-Reference (Sharing Remote Objects / Stubs)
Any class that implements `java.rmi.Remote` (like `ArenaListener`):
* When your teammate calls `server.join("Joel", listenerStub)`, Java **does NOT copy the listener object**.
* Instead, it creates a tiny **Remote Stub** containing your teammate’s IP and port 1101, and gives that stub to the server.
* When your server calls `listener.onFrame()`, it uses that reference to dial back to your teammate’s machine.

---

### 2. Is the data being stored anywhere?

#### A. The Game State: 100% In-Memory (RAM only)
* **There is NO database** (no SQL, no MongoDB, no Redis, no save files).
* All ship positions, scores, laser beams, and active sessions live in your computer's **RAM (Java JVM Heap)**:
  * In `GameWorld.java`: in-memory Java `ArrayList` and `Map` collections.
  * In `ArenaServiceImpl.java`: an in-memory `ConcurrentHashMap<Long, Session>`.
* **If you close or restart the server:** Everything resets to zero immediately. State is completely ephemeral.

#### B. The File System: On the Server's Hard Drive (`shared/`)
* The only data stored on a physical hard drive is the **`shared/` folder** on your laptop (where `ping_results.csv` and `rmi_jokes.txt` live).
* `FileServiceImpl` reads this directly from your disk using standard Java NIO (`Files.list()`).
* **Important detail:** When a player shoots and destroys a crate in the game (`"Joel ran rm homework_FINAL.txt"`), it is **only destroyed in the game's RAM**—the actual file on your laptop's disk is **never deleted**!

---

### 💡 The 1-Sentence Summary for the Class:
> *"All real-time game state—ship positions, health, and sessions—is kept **100% in-memory** on the server's heap for maximum speed, while data travels between machines via **Java Object Serialization** over TCP sockets without any external database."*
