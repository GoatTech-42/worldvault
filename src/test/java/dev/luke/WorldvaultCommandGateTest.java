package dev.luke;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.EnvType;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2: who may run the commands. Checks the registered command tree and the runtime gate; it does not start an
 * integrated, LAN or dedicated server, so those remain unverified live.
 */
class WorldvaultCommandGateTest {
    static final String[] COMMANDS = {
            "worldvault list",
            "worldvault store f.bin 1 64 1",
            "worldvault store f.bin chests 1 64 1 3 64 1",
            "worldvault store f.bin range 0 64 0 4 64 4",
            "worldvault restore f.bin",
            "worldvault repair f.bin",
    };

    @BeforeAll static void initializeGame() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static boolean parses(CommandDispatcher<CommandSourceStack> d, String command, PermissionSet permissions) {
        var result = d.parse(command, net.minecraft.commands.Commands.createCompilationContext(permissions));
        return !result.getReader().canRead() && result.getExceptions().isEmpty() && result.getContext().getCommand() != null;
    }

    @Test void everyCommandNeedsOwnerPermissionLevel() {
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        WorldvaultMod.register(d);
        for (String command : COMMANDS) {
            assertTrue(parses(d, command, LevelBasedPermissionSet.OWNER), "owner cannot run: " + command);
            for (PermissionSet lower : new PermissionSet[]{PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.MODERATOR,
                    LevelBasedPermissionSet.GAMEMASTER, LevelBasedPermissionSet.ADMIN}) {
                assertFalse(parses(d, command, lower), "level below owner can run: " + command + " with " + lower);
            }
        }
    }

    @Test void dedicatedServerRegistersNoCommands() {
        assertFalse(WorldvaultMod.registersOn(EnvType.SERVER));
        assertTrue(WorldvaultMod.registersOn(EnvType.CLIENT));
    }

    @Test void onlyTheSingleplayerOwnerMayRunThem() {
        assertNull(WorldvaultMod.refusal(false, true, true), "the host of a singleplayer world");
        assertNotNull(WorldvaultMod.refusal(false, true, false), "a LAN guest on the host's integrated server");
        assertNotNull(WorldvaultMod.refusal(true, false, false), "a dedicated server");
        assertNotNull(WorldvaultMod.refusal(true, false, true), "a dedicated server, even for an op");
    }
}
