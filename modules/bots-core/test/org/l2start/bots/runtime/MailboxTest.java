package org.l2start.bots.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.Pos;

class MailboxTest {
    @Test
    void keepsOrderAndEmptiesOnDrain() {
        final Mailbox m = new Mailbox();
        m.offer(new BotEvent.Killed(1, 10));
        m.offer(new BotEvent.Whisper("a", "hi"));
        final List<BotEvent> l = m.drain();
        assertEquals(2, l.size());
        assertInstanceOf(BotEvent.Killed.class, l.get(0));
        assertEquals(0, m.size());
        assertTrue(m.drain().isEmpty());
    }

    @Test
    void boundedDropsOldest() {
        final Mailbox m = new Mailbox(64);
        for (int i = 0; i < 100; i++) {
            m.offer(new BotEvent.Killed(i, 1));
        }
        final List<BotEvent> l = m.drain();
        assertEquals(64, l.size());
        assertEquals(36, ((BotEvent.Killed) l.get(0)).npcObjectId());
        assertEquals(36, m.dropped());
    }

    @Test
    void collapsesSameKeyAndSumsDamage() {
        final Mailbox m = new Mailbox();
        m.offer(new BotEvent.Attacked(7, false, 10));
        m.offer(new BotEvent.Killed(5, 1));
        m.offer(new BotEvent.Attacked(7, false, 5));
        m.offer(new BotEvent.Attacked(8, true, 1));
        m.offer(new BotEvent.Teleported(new Pos(0, 0, 0)));
        m.offer(new BotEvent.Teleported(new Pos(1, 1, 1)));
        final List<BotEvent> l = m.drain();
        assertEquals(4, l.size(), l.toString());
        final BotEvent.Attacked a = (BotEvent.Attacked) l.get(1);
        assertEquals(7, a.attackerId());
        assertEquals(15, a.damage(), 1e-9);
        assertEquals(new Pos(1, 1, 1), ((BotEvent.Teleported) l.get(3)).to());
        assertEquals(2, m.collapsed());
    }

    @Test
    void manyProducers() throws Exception {
        final Mailbox m = new Mailbox(10_000);
        final CountDownLatch go = new CountDownLatch(1);
        final Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t++) {
            final int base = t * 1000;
            ts[t] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 1000; i++) {
                    m.offer(new BotEvent.Killed(base + i, 1));
                }
            });
            ts[t].start();
        }
        go.countDown();
        int got = 0;
        while (got < 4000) {
            got += m.drain().size();
            Thread.onSpinWait();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(4000, got);
    }
}
