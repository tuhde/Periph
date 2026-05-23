package it.uhde.periph.chips.io_expander;

import it.uhde.periph.transport.Transport;

import java.io.IOException;
import java.util.function.IntConsumer;

public class Pcf8575Full extends Pcf8575Minimal {

    private int[] prev = {0xFF, 0xFF};
    private IntConsumer callback = null;
    private Thread pollThread = null;
    private volatile boolean pollStop = false;

    public Pcf8575Full(Transport transport) throws IOException {
        super(transport);
        byte[] buf = transport.read(2);
        prev[0] = buf[0] & 0xFF;
        prev[1] = buf[1] & 0xFF;
    }

    public void configureInterrupt(IntConsumer callback) {
        this.callback = callback;
        pollStop = false;
        pollThread = new Thread(this::pollLoop);
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        try {
            while (!pollStop) {
                byte[] current = transport.read(2);
                int ch0 = (current[0] ^ prev[0]) & 0xFF;
                int ch1 = (current[1] ^ prev[1]) & 0xFF;
                int changed = ch0 | (ch1 << 8);
                if (changed != 0 && callback != null) {
                    prev[0] = current[0] & 0xFF;
                    prev[1] = current[1] & 0xFF;
                    callback.accept(changed);
                }
                Thread.sleep(5);
            }
        } catch (Exception e) { }
    }

    public int clearInterrupt() throws IOException {
        byte[] current = transport.read(2);
        int ch0 = (current[0] ^ prev[0]) & 0xFF;
        int ch1 = (current[1] ^ prev[1]) & 0xFF;
        prev[0] = current[0] & 0xFF;
        prev[1] = current[1] & 0xFF;
        return ch0 | (ch1 << 8);
    }

    public void stopInterrupt() {
        pollStop = true;
        if (pollThread != null) pollThread.interrupt();
    }
}