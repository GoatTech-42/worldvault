# Worldvault

Experimental Fabric 1.21.11 mod. Current build is for local singleplayer worlds, not random public servers. This is not a cloud backup: deleting the world also deletes the vault.

Files are Base64-encoded into written books inside a chest in your world. The index in `world/worldvault/index.tsv` stores only a name and chest coordinates; the chest books hold the file data. Each book holds 2,048 file bytes, and a single chest holds up to about 54 KB. The decoder checks the file size and CRC32 before exporting.

To try it in a disposable world:

1. Install Fabric Loader and Fabric API for 1.21.11, and put this mod jar in `mods`.
2. Start a singleplayer world once to create the save. Put a small file in `saves/<world>/worldvault/in/`. Create that folder if it is not there yet.
3. Put an empty single chest in the world and bring enough book-and-quills: one per 2 KB of file, rounded up. Each book-and-quill uses a book, feather and ink sac; the command consumes them when it writes the books.
4. Stand within eight blocks and run `/worldvault store filename.ext X Y Z` with the chest block's coordinates. Only a simple filename is accepted, without spaces or path separators. The chest books are physical items, so don't break or replace the chest if you want the file back.
5. Run `/worldvault list`, then `/worldvault restore filename.ext` from anywhere in the same world. The recovered file appears in `saves/<world>/worldvault/out/`. It will not overwrite an existing output; move the file before restoring it again.

The command needs the chest's chunk, which may load briefly when restoring from far away. World damage, chest tampering, missing books, or a bad checksum causes a failure rather than silently producing an altered file. Keep backups of important files. This is an early build awaiting in-game testing, not a safe place for your only copy.

Build: JDK 21, Gradle 9.2.1, Fabric Loom 1.14.10. Run `gradle build`; the remapped jar is in `build/libs/`.

Note: a double chest is not supported in this build. Don't put the vault chest flush against another chest later; the restore command will ask you to separate them first. Restores load the chest's chunk temporarily, so large worlds may pause briefly.
