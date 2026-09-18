package com.herasgarden.gardenlands.ui;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import org.bukkit.command.CommandSender;

public final class LandsMessages {
    private LandsMessages() {
    }

    public static void send(CommandSender sender, String message) {
        GardenMessages.send(sender, message);
    }
}
