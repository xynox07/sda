# UltimateCore (Paper 1.21.11)

Trade GUI, protected areas, warps, land claims, emotes, PvP arena and kits - in ONE plugin.

## Build (needs JDK 21 + Maven)
    mvn clean package
The plugin is created at `target/UltimateCore.jar` -> copy it into your server `plugins/` folder (Paper 1.21.11) and restart.

## Commands
| Command | What it does |
|---|---|
| /trade <player> | Request a trade. Other player clicks [ACCEPT] or /trade accept. Left side = you, right side = them. Green glass = OK, red glass = NOT OK. Changing any item resets both OKs. |
| /area axe | Get the gold axe. Left click = pos 1, right click = pos 2 (full height, up & down) |
| /area create <name> | Make a protected area from your selection |
| /area <name> edit | Menu: Fight / Place / Break / Explosions / Doors-chests / Mob spawning = YES or NO |
| /area delete <name>, /area list, /area info | |
| /warp <name> | Teleport |
| /warp add <name>, /warp del <name>, /warp list | Admin: add/remove warps |
| /claim tool | Golden shovel: left/right click 2 corners, then /claim create |
| /claim create, delete, list, info, trust <p>, untrust <p> | Manage claims |
| /claim points | See your claim blocks (start 777, +2 per minute online) |
| /claim points <amount> <player> | Admin: give claim blocks |
| /sit, /spin [player], /dap <player>, /hug <player>, /wave <player>, /highfive <player> | Emotes |
| /arena create <name> | Admin: set arena spawn where you stand |
| /arena join [arena] [kit], /arena leave | Play in the arena. Your real items are saved and come back on leave |
| /kit <name>, /kit list | Change kit inside the arena |
| /kit create <name>, /kit edit <name>, /kit delete <name> | Owner only. Edit window saves when you close it |

Default kits: sword, mace, ufc. Everything (admin/owner) uses permissions that default to OP:
ultimate.owner, ultimate.area.admin, ultimate.area.bypass, ultimate.claim.admin,
ultimate.warp.admin, ultimate.arena.admin, ultimate.emote.others.

Settings (claim blocks, arena default kit, allowed commands) are in `plugins/UltimateCore/config.yml`.
Tip: inside a protected /area, set Fight = YES for the place where your arena is.
