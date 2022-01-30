package ru.leymooo.botfilter;

import net.md_5.bungee.UserConnection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ReAuth {

    static AtomicInteger connectionId = new AtomicInteger(0);

    Map<String, String> mapping = new ConcurrentHashMap<>();

    static int a = 0;

    public static String generateConnectionName() {
        return "connection" + connectionId.incrementAndGet();
    }

    public static String getName(UserConnection user) {
        return "test" + Integer.toString(a++);
    }

}
