# pt-mcp

A local **Model Context Protocol** server that lets an MCP-aware client (e.g.
Claude Code, Claude Desktop) drive **Cisco Packet Tracer**: create devices,
connect cables, configure them via IOS CLI, and read the topology back.

The server is written in Java and wraps Cisco's official `pt-cep-java-framework`
(the only first-party SDK for Packet Tracer's IPC protocol — no equivalent
exists for Python or JS).

## Status

Working end-to-end. Tools exposed:

| Tool | Purpose |
|---|---|
| `pt_get_topology` | List devices currently on the canvas + link count. |
| `pt_add_device` | Add a device (router/switch/pc/...) by model and coords. |
| `pt_delete_device` | Remove a device by name. |
| `pt_get_ports` | List interfaces of a device. |
| `pt_connect_devices` | Cable two interfaces together. |
| `pt_skip_boot` | Skip the simulated boot on a router/switch (~30-60s). |
| `pt_run_cli` | Send an IOS command and get back the output. |
| `pt_set_endpoint_ip` | Set a static IP/mask (and optional gateway) on an end-device (PC/Laptop/Server). Turns off DHCP first. |
| `pt_set_endpoint_dhcp` | Switch an end-device's interface to DHCP. |
| `pt_power` | Power any device on/off (`Device.setPower`). Re-powering a router restarts its boot. |
| `pt_save_file` | Save the current `.pkt` to a path on the machine running PT. |
| `pt_open_file` | Open a `.pkt` from a path on the machine running PT. |
| `pt_get_device_models` | List PT's device catalog: categories with no arg, models for a given category (e.g. `Routers`). |

## Requirements

- **Cisco Packet Tracer 9.0.0** (other 9.x may work; not tested).
- **JDK 21** (Temurin or any other distribution).
- An MCP client. Tested with Claude Code; should work with anything that
  speaks stdio MCP.

## Setup

### 1. Clone and copy Cisco's SDK jar

The SDK is proprietary and is **not** redistributed in this repo. Copy it from
your Packet Tracer install:

```
<PT install>\help\default\ipc\pt-cep-java-framework-9.0.0.0.jar
```

…into `libs/` at the repo root.

On Windows the default install path is:
`C:\Program Files\Cisco Packet Tracer 9.0.0\help\default\ipc\`.

### 2. Pick your own credentials

The MCP server authenticates to Packet Tracer with an application ID and a
shared secret. **Choose your own values**; do not reuse defaults from any
example. Two things must agree on the same values:

- The `<ID>` and `<KEY>` inside `pta/ptmcp/ptmcp.xml`.
- The credentials read by the Java client at runtime.

#### 2a. Generate `ptmcp.xml`

```sh
cp pta/ptmcp/ptmcp.xml.template pta/ptmcp/ptmcp.xml
```

Edit `pta/ptmcp/ptmcp.xml` and replace:
- `<ID>com.example.ptmcp.connector</ID>` → e.g. `com.yourname.ptmcp.connector`
- `<KEY>REPLACE_WITH_YOUR_OWN_SECRET</KEY>` → a strong random string

#### 2b. Encrypt the XML into a `.pta`

Packet Tracer ships with `meta.exe` that signs/encrypts the XML:

```sh
"C:\Program Files\Cisco Packet Tracer 9.0.0\bin\meta.exe" pta/ptmcp/ptmcp.pta pta/ptmcp/ptmcp.xml
```

#### 2c. Tell PT about the app

1. Copy `pta/ptmcp/ptmcp.pta` to your user extensions folder:
   `%USERPROFILE%\Cisco Packet Tracer 9.0.0\extensions\ptmcp\ptmcp.pta`
2. In Packet Tracer: **Extensions > IPC > Configure Apps > Add** → pick the
   `.pta` → mode **On Demand**.
3. **Extensions > IPC > Options** → tick **Listening** on port `39000` and
   **Always Listen On Start**.

#### 2d. Tell the Java client the same credentials

Two equivalent ways:

**Environment variables (preferred for production):**
```sh
export PT_MCP_AUTH_APPLICATION="com.yourname.ptmcp.connector"
export PT_MCP_AUTH_SECRET="your-strong-random-string"
```

**`local.properties` file (handy for local dev):**
```sh
cp local.properties.example local.properties
# edit local.properties with your values
```

`local.properties` is gitignored.

### 3. Build

```sh
./gradlew installDist
```

The executable launchers land in `build/install/pt-mcp/bin/` as `pt-mcp`
(POSIX) and `pt-mcp.bat` (Windows).

### 4. Smoke test (optional)

With Packet Tracer running and listening:

```sh
./smoke-mcp.sh
```

You should see the `initialize` response, the list of 13 tools, and a
`pt_get_topology` result.

## Hooking it up to Claude Code

```sh
claude mcp add pt-mcp -- /absolute/path/to/build/install/pt-mcp/bin/pt-mcp
```

On Windows use the `.bat` launcher:

```sh
claude mcp add pt-mcp -- "C:\path\to\build\install\pt-mcp\bin\pt-mcp.bat"
```

Add `-s user` to make the registration global (any project), otherwise it is
scoped to the directory you ran the command in.

Restart Claude Code. The 13 tools should show up under the `pt-mcp` server.

## Usage

Once the server is wired up, prompt your MCP client in natural language. Some
examples that exercise the tools end-to-end:

> "Using pt-mcp, give me the current topology."

> "Create a 2911 router at (200, 200), skip its boot, and list its interfaces."

> "Build a topology with two 2911 routers connected via GigabitEthernet0/0
> using a cross cable. Skip their boot and configure 10.0.0.1/24 on R1's
> Gig0/0 and 10.0.0.2/24 on R2's Gig0/0. Verify with `show ip interface brief`."

A few practical tips:

- The client picks the order of tool calls; you describe the goal, not the
  steps. For multi-step work, mention `pt_skip_boot` explicitly so it does
  not get forgotten on freshly-created Cisco devices.
- Tool calls go through one persistent IPC session, so CLI state is preserved
  between consecutive `pt_run_cli` calls in the same turn (e.g. after
  `interface gig0/0` in `global` mode, the next command with no mode runs in
  interface-config).
- If you close and reopen Packet Tracer mid-conversation, the next tool call
  will detect the dead session and reconnect transparently.

## Project layout

```
src/main/java/com/ptmcp/
  PtIpcClient.java       # Wraps the Cisco SDK with a simple Java API.
  ConnectionManager.java # Persistent session + lazy reconnect.
  PtMcpServer.java       # MCP stdio server; registers the 13 tools.
  Main.java              # Smoke test against PT (no MCP layer).
src/main/resources/
  simplelogger.properties # Forces logs to stderr (stdio MCP uses stdout).
pta/ptmcp/
  ptmcp.xml.template     # Template; rename to ptmcp.xml and fill in.
libs/
  pt-cep-java-framework-9.0.0.0.jar   # NOT in repo; you supply it.
```

## Notes / gotchas

- **Routers take 30-60s to boot** in PT's simulation. Until they finish, IOS
  commands return `ERROR_INVALID`. Call `pt_skip_boot` right after
  `pt_add_device` for routers/switches.
- The 2nd argument of `pt_add_device` is the **model** (e.g. `"2911"`), not a
  visible name. Packet Tracer assigns the name (e.g. `"Router0"`) and the tool
  returns it.
- Adding the first PC causes PT to silently add a `Power Distribution Device0`
  artifact (something IoT-related). It is harmless; ignore or filter it.
- `pt_save_file` / `pt_open_file` paths are resolved on the machine **running
  Packet Tracer**, not the MCP client. Use absolute paths.
- `pt_set_endpoint_ip` turns DHCP off before applying the static address (PT
  otherwise ignores it); `pt_set_endpoint_dhcp` is the inverse.
- End-devices (PC/Laptop/Server) are not Cisco IOS devices, so they take no
  `pt_run_cli` — configure them with the `pt_set_endpoint_*` tools instead.
- `pt_get_device_models` is case/plural-sensitive: pass a category exactly as
  returned (e.g. `Routers`, not `router`).
- Logs go to **stderr** by design; the stdio MCP protocol owns stdout.

## Acknowledgments

Sparked by a TikTok video showing someone wiring an LLM to a network simulator.
Built as a learning project to get hands-on with Java, the MCP Java SDK and
Cisco's Packet Tracer IPC protocol — none of which had any prior excuse to
exist in my toolbelt.

Cisco's `pt-cep-java-framework` does the heavy lifting on the wire-protocol
side; this repo is mostly a thin, opinionated wrapper around it.

## License

The Java code in this repo is provided as-is. The Cisco SDK and Packet Tracer
itself are subject to Cisco's own licensing terms — they are not redistributed
here.
