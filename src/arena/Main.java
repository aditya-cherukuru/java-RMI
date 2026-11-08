package arena;

import arena.bot.Bot;
import arena.client.ArenaClient;
import arena.client.LsClient;
import arena.server.ArenaServer;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/**
 * Entry point of rmi-arena.jar.
 *
 *   server [--host IP] [--dir FOLDER] [--kills N] [--minutes M] [--headless]
 *   client [server-ip] [name] [--pull]
 *   bot    <server-ip> [count]
 *   ls     <server-ip> [path]
 *
 * Double-clicking the jar (no arguments) shows a small launcher.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : launcher();
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (mode) {
            case "server" -> ArenaServer.main(rest);
            case "client" -> ArenaClient.main(rest);
            case "bot" -> Bot.main(rest);
            case "ls" -> LsClient.main(rest);
            default -> usage();
        }
    }

    private static String launcher() {
        if (GraphicsEnvironment.isHeadless()) return "help";
        String[] options = {"Host a game (server)", "Join a game (client)"};
        int choice = JOptionPane.showOptionDialog(null,
                "RMI Arena - a Java RMI laser battle.\nOne laptop hosts, the others join.",
                "RMI Arena", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        return switch (choice) {
            case 0 -> "server";
            case 1 -> "client";
            default -> {
                System.exit(0);
                yield "help";
            }
        };
    }

    private static void usage() {
        System.out.println("""
                RMI Arena - Multiplayer Battle over Java RMI

                  java -jar rmi-arena.jar server [--host IP] [--dir FOLDER] [--kills N] [--minutes M] [--headless]
                  java -jar rmi-arena.jar client [server-ip] [name] [--pull]
                  java -jar rmi-arena.jar bot    <server-ip> [count]
                  java -jar rmi-arena.jar ls     <server-ip> [path]
                """);
    }
}
