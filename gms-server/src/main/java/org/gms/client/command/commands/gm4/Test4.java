package org.gms.client.command.commands.gm4;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;

public class Test4 extends Command{
    {
        setDescription("Test4.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
//        if (params.length < 1) {
//            player.yellowMessage("Syntax: test");
//            return;
//        }
        System.out.println("Test4 executed!");

    }

}

