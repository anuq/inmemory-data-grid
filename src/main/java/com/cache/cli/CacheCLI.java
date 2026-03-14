package com.cache.cli;

import com.cache.protocol.BinaryProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Interactive CLI client for the cache server.
 *
 * Usage:
 *   java -jar cache.jar cli [host] [port]
 *
 * Commands:
 *   get <key>
 *   set <key> <value> [ttl_ms]
 *   del <key>
 *   stats
 *   exit
 */
public class CacheCLI {

    private static final int BUFFER_SIZE = 64 * 1024;

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int    port = 6379;

        // args: cli [host] [port]
        if (args.length >= 2) host = args[1];
        if (args.length >= 3) port = Integer.parseInt(args[2]);

        System.out.println("Connecting to cache server at " + host + ":" + port);

        try (var channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(host, port));
            System.out.println("Connected. Type 'help' for commands.\n");

            var scanner = new Scanner(System.in);
            while (true) {
                System.out.print("cache> ");
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isBlank()) continue;

                String[] parts = line.split("\\s+", 4);
                String   cmd   = parts[0].toLowerCase();

                try {
                    switch (cmd) {
                        case "get"  -> doGet(channel, parts);
                        case "set"  -> doSet(channel, parts);
                        case "del", "delete" -> doDel(channel, parts);
                        case "stats" -> doStats(channel);
                        case "exit", "quit" -> { System.out.println("Bye!"); return; }
                        case "help" -> printHelp();
                        default -> System.out.println("Unknown command: " + cmd + ". Type 'help'.");
                    }
                } catch (IOException ex) {
                    System.err.println("Connection error: " + ex.getMessage());
                    return;
                }
            }
        } catch (IOException ex) {
            System.err.println("Failed to connect: " + ex.getMessage());
        }
    }

    private static void doGet(SocketChannel ch, String[] parts) throws IOException {
        if (parts.length < 2) { System.out.println("Usage: get <key>"); return; }
        send(ch, BinaryProtocol.encodeGet(parts[1]));
        var resp = receive(ch);
        if (resp.isOk()) {
            System.out.println(resp.payloadAsString());
        } else if (resp.isNotFound()) {
            System.out.println("(nil)");
        } else {
            System.out.println("ERROR: " + resp.payloadAsString());
        }
    }

    private static void doSet(SocketChannel ch, String[] parts) throws IOException {
        if (parts.length < 3) { System.out.println("Usage: set <key> <value> [ttl_ms]"); return; }
        String key   = parts[1];
        byte[] value = parts[2].getBytes(StandardCharsets.UTF_8);
        long   ttl   = parts.length >= 4 ? Long.parseLong(parts[3]) : 0L;
        send(ch, BinaryProtocol.encodeSet(key, value, ttl));
        var resp = receive(ch);
        System.out.println(resp.isOk() ? "OK" : "ERROR: " + resp.payloadAsString());
    }

    private static void doDel(SocketChannel ch, String[] parts) throws IOException {
        if (parts.length < 2) { System.out.println("Usage: del <key>"); return; }
        send(ch, BinaryProtocol.encodeDelete(parts[1]));
        var resp = receive(ch);
        System.out.println(resp.isOk() ? "OK" : "ERROR: " + resp.payloadAsString());
    }

    private static void doStats(SocketChannel ch) throws IOException {
        send(ch, BinaryProtocol.encodeStats());
        var resp = receive(ch);
        System.out.println(resp.payloadAsString());
    }

    private static void send(SocketChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) ch.write(buf);
    }

    private static BinaryProtocol.Response receive(SocketChannel ch) throws IOException {
        var buf = ByteBuffer.allocate(BUFFER_SIZE);
        while (true) {
            ch.read(buf);
            buf.flip();
            var resp = BinaryProtocol.tryDecodeResponse(buf);
            if (resp != null) return resp;
            buf.compact();
        }
    }

    private static void printHelp() {
        System.out.println("""
            Commands:
              get  <key>                    - Retrieve a value
              set  <key> <value> [ttl_ms]   - Store a value (optional TTL in ms)
              del  <key>                    - Delete a key
              stats                         - Show cache statistics
              exit                          - Disconnect and exit
            """);
    }
}
