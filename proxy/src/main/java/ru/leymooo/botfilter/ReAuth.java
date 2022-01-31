package ru.leymooo.botfilter;

import java.util.concurrent.atomic.AtomicInteger;

public class ReAuth {

    static AtomicInteger connectionId = new AtomicInteger(0);

    public static String generateConnectionName() {
        return "connection" + connectionId.incrementAndGet();
    }


}
