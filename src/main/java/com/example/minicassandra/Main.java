package com.example.minicassandra;

import com.example.minicassandra.config.DatabaseConfig;
import com.example.minicassandra.server.MiniCassandraServer;

import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        MiniCassandraServer server = new MiniCassandraServer(config, Path.of(config.dataDir()));
        server.start();
    }
}
