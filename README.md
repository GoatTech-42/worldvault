# Worldvault

Experimental Fabric 1.21.11 mod. Current build is for local singleplayer worlds, not random public servers. This is not a cloud backup: deleting the world also deletes the vault.

Files are Base64-encoded into written books inside a chest in your world. The index in `world/worldvault/index.tsv` stores only a name and chest coordinates; the chest books hold the file data. Each book holds 2,048 file bytes, and a single chest holds up to about 54 KB and a double chest up to about 108 KB. The decoder checks the file size and CRC32 before exporting.

To try it in a disposable world:

1. Install Fabric Loader and Fabric API for 1.21.11, and put this mod jar in `mods`.
2. Start a singleplayer world once to create the save. Put a small file in `saves/<world>/worldvault/in/`. Create that folder if it is not there yet.
3. Put an empty single chest in the world and bring enough book-and-quills: one per 2 KB of file, rounded up. Each book-and-quill uses a book, feather and ink sac; the command consumes them when it writes the books.
4. Stand within eight blocks and run `/worldvault store filename.ext X Y Z` with the chest block's coordinates. Only a simple filename is accepted, without spaces or path separators. The chest books are physical items, so don't break or replace the chest if you want the file back.
5. Run `/worldvault list`, then `/worldvault restore filename.ext` while standing near the chests (their chunks must be loaded). The recovered file appears in `saves/<world>/worldvault/out/`. It will not overwrite an existing output; move the file before restoring it again.

Store and restore never load a chunk: if a chest, the other half of a double chest, or any part of a range is in an unloaded chunk, the command fails before touching anything and asks you to go closer. World damage, chest tampering, missing books, or a bad checksum causes a failure rather than silently producing an altered file. Keep backups of important files. This is an early build, not a safe place for your only copy. The automated save/reload checks below do not replace trying it in a disposable singleplayer world first.

Build: JDK 21, Gradle 9.2.1, Fabric Loom 1.14.10. Run `gradle build`; the remapped jar is in `build/libs/`.

Double chests: you can store into two paired chest blocks (both halves must be empty). Books fill the left half's 27 slots first (slots 0-26), then the right half's (27-53); "left" and "right" are the chest blocks' own LEFT/RIGHT state, so it does not matter which half you click. The index remembers that the vault is a double chest, and restore requires the same layout: if you break one half, or pair a chest next to a vault that was stored in a single chest, the restore fails with a clear message instead of guessing. Separate them again and retry. Restores need the chest's chunk loaded; they do not load it for you.


## Verification

Run `gradle test` to check the codecs, locator index, and real Minecraft chest serialization. The chest test writes file bytes into written-book items, saves the chest to compressed NBT with the game's own serializer, loads a new chest from that file, and checks every restored byte. Fixtures cover empty files, book-boundary sizes, 8 KB, and 54 KB. Other cases check reordered slots, missing books, foreign items, occupied chests, and files that exceed one chest. All tests run without starting a server or touching an existing world.

The September 26 disposable-server smoke test also exercised store/list/restore, survival book cost, double-chest rejection, and output overwrite protection. That test used a temporary build with the singleplayer gate relaxed. The shipped mod keeps the gate; an integrated singleplayer-client playtest is still a separate check.

## Multi-chest vaults (v2)

A file can span several chests, in order, one chunk per chest (CRC32 each, SHA-256 for the whole file):

- `/worldvault store <name> <x y z>` one chest (or double chest), as before.
- `/worldvault store <name> chests <c1> <c2> [... <c8>]` an ordered list of chests.
- `/worldvault store <name> range <from> <to>` every chest in the box, ordered by x, then y, then z (a double chest counts once). Limits: 4,096 blocks, 64 chests.

Only as many chests as the file needs are used, in order, and all of them must be empty, unlisted by another vault and not overlapping. Everything is checked before the first book is written; book-and-quills are consumed only after success. The chests are written first and the index row last. If a write fails, every chest is put back slot by slot as it was; if putting a chest back fails too, the error (and the game log) names each chest that may still hold books, which are then plain items with no index row. Restore reads and verifies every chest (chest N is named in the error), including the number of books the index recorded for it, then writes `worldvault/out` only if all checks pass. A missing chest, a changed book or a swapped chunk fails with nothing written. v1 vaults still restore; the index file becomes v2 the next time something is stored.

The explicit `chests` list takes up to 8 chests and `range` up to 64. The list is short because each coordinate is another nested command node and three more numbers to type; for larger vaults use `range`. Both require every chest within eight blocks of you.

### Integrity: v1 and v2 vaults

A v2 vault records a CRC32 for each chest's chunk and a SHA-256 of the whole file; restore checks both, plus the size and the CRC32 inside the books. A vault stored by an older build (a v1 index row, shown as "v1, CRC32 only" in `/worldvault list`) recorded none of those: only the CRC32 inside its books and the book count protect it, and the restore message says so. CRC32 catches accidental damage, not deliberate tampering.

### Repair

`/worldvault repair <name>` checks one index row against its chests. If every chest is loaded and none holds a Worldvault book (a broken chest counts as empty), the row points at nothing and is removed, freeing the name and the coordinates. For a vault stored in a double chest, the four chests beside the recorded half must be loaded and checked too, because the other half may still hold books after the recorded half was broken or the pair was split; a neighbouring chest with any Worldvault book keeps the row. If the vault verifies, or any chest still holds Worldvault books, or a chest is not loaded, the row is kept and the message says which chest. A row like that can appear when the game crashes after a store but before the world saves: chest contents reach disk at the next world save, while the index is written at once.

### Who can run the commands

- Not registered on a dedicated server: the commands read and write files on the machine running the server.
- On an integrated (singleplayer) server: owner permission level (4), which the host has when cheats are allowed, and the player running it must be the world's host. With cheats off the commands are unavailable.
- Opened to LAN: other players are refused even if they have cheats or op, because `worldvault/in` and `worldvault/out` are on the host's disk.

These rules are checked in unit tests against the command tree and the gate logic only. Integrated-server, LAN and dedicated-server behavior is unverified and needs a live test.

### Hoppers and other changes between store and restore

Every command runs on the server thread. A store's checks and writes happen in the same tick, so nothing can change a chest in between. After that, anything can: a hopper under a vault chest pulls the books out one by one. Restore then fails, naming the chest, and writes nothing. Put the books back, or use `repair` once no chest holds any of them. Don't put vault chests above hoppers.

### Input files

Only regular files that are really inside `worldvault/in` are read. A symbolic link inside the folder is followed only when it resolves to a file still inside `worldvault/in`.

### Legacy stack codec

`WorldvaultCodec.encode`/`decode` (one byte per item stack) is the earliest prototype format. No command uses it; v1 and v2 vaults are both written books. It is kept with its test so that format stays readable.
