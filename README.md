# DarlooPolice (Paper 1.21.11)

Punish/jail, shop, tpa, lag tools, trade, protected areas, warps, land claims, emotes, PvP arenas and kits - in ONE plugin.

## Build
GitHub: push everything to the ROOT of your repo (`pom.xml`, `src`, `.github`). The Actions tab builds the jar - download it from Artifacts.
Local: JDK 21 + Maven, run `mvn clean package` -> `target/DarlooPolice.jar` -> copy into the server `plugins/` folder.

## Commands
| Command | What it does |
|---|---|
| /register <pass> <pass>, /login <pass> | LOGIN SECURITY. Until logged in: frozen, blind, no chat/commands, "DarlooPolice Login" title on screen. /changepassword, /authreset <player> (admin) |
| /punish <player> <minutes> [reason], /unpunish <player> | Jail a player (timer only counts while online). Items are saved and given back when free. |
| /jail set / del / list | /jail set = 5x5 cell. Only chests, milk, rotten flesh. 1 heart damage every 2 s, death = respawn in jail. |
| /shop | Category menu: Building Blocks, Decoration, Food, Other. Click an item to buy (you pay with items). |
| /shop edit | Owner: pick a category, left 4 columns = items, right 4 columns = price (any item: iron, emerald...). Close to save. |
| Shift+Left / Shift+Right on an item in the shop | Owner: price UP +1 / DOWN -1 |
| /arena create <name> | Admin: set an arena where you stand. Name it like a kit (e.g. `/arena create crystal`) and everyone who picks that kit is teleported THERE. |
| /arena join [kit] | Join. No kit = default arena + default kit. `/arena join crystal` = crystal arena. |
| /arena modes | Menu to pick a game mode. /arena leave = kit cleared, your items back. |
| /kit starter | EVERYONE: free starter kit (iron armor, iron sword, 16 oak logs, 16 bread). Once per player by default (config `starter.cooldown-hours`). Owner edits it with `/kit edit starter`. |
| /kit <name> | Inside the arena: switch mode (moved to that mode's arena). Owner: /kit create, edit, delete. |
| /tpa <p>, /tpahere <p>, /tpaccept, /tpdeny, /tpcancel | Teleport requests with clickable [ACCEPT] [DENY], 3 s countdown (cancelled if you move). |
| /clearlag, /boost | Admin: remove old dropped items, xp orbs, stuck arrows and extra mobs. Also runs automatically (see config). |
| /lag (/tps), /ping [player] | TPS, ms per tick, memory, entities, ping in ms |
| //wand, //pos1, //pos2, //sel, //expand vert, //set <block>, //replace <from> <to>, //walls, //sphere, //copy, //paste, //undo | Mini WorldEdit (permission darloo.edit). //wand = wooden axe: left click pos 1, right click pos 2. `//expand vert` = up and down. Patterns: `stone`, `50%stone,50%dirt`, `air` |
| /sethome [name], /home [name], /delhome <name>, /homes | Homes: every player has 4 (config `homes.default-limit`). More: permission `darloo.homes.<number>` (e.g. darloo.homes.10) or `darloo.homes.unlimited`. Who may set / delete: `darloo.home.set`, `darloo.home.delete` (on for everybody by default). 3 s countdown, cancelled if you move. |
| /announcement <text> (also /announce) | Send an announcement to everybody, e.g. `/announcement welcome to the server`. `&` color codes work inside the text. |
| /announcement edit | Owner: pick the color (16 colors), bold / italic / underline, FONT (default, uniform, alt, illager), SOUND, where it shows (chat, big title, action bar, all) and the [Announcement] prefix. Live preview on the sign. |
| /announcement add <text>, list, remove <n> | Automatic announcements that repeat every N minutes (set the time in the editor) |
| /mems, /mems edit, /mems test <meme> | Owner: edit the memes - GHOP GHOP (punished) and NOOB (fall death in PvP): text, sound, repeat, on/off |
| /kit edit | Owner: pick a kit, edit it. Nether star = item browser with search (every item). Shift+Right click an item = max enchant |
| /trade, /area, /warp, /claim, /sit, /spin, /dap /hug /wave /highfive | as before |

Area flag `Arena`: set it to YES in `/area <name> edit` and anybody who walks out of that area dies (teleports, e.g. /arena leave, are safe).

Crystal PvP tip: for the crystal arena make a protected `/area` with Fight = YES and Explosions = NO (so crystals hurt players but do not destroy the map).

Permissions (all default to OP): darloo.police, darloo.admin, ultimate.owner, ultimate.area.admin, ultimate.area.bypass,
ultimate.claim.admin, ultimate.warp.admin, ultimate.arena.admin, ultimate.emote.others.
