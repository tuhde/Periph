package it.uhde.periph.chips.light;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Si1133Test {

    /** Simulates the command engine: RESPONSE0 counter, parameter table, FORCE packing HOSTOUT. */
    static class Mock extends MockConnection {
        int partId = 0x33;
        final int[] params = new int[64];
        int counter = 0x0F, error = 0, resp1 = 0, irqEnable = 0, irqStatus = 0;
        int[] hostout = new int[26];
        final int[] samples = {0x012345, -5, 0xABCD, 0, 0, 0};
        final List<Integer> commands = new ArrayList<>();
        boolean ignoreCommands = false;
        InputPin pin;

        int resp0() {
            return error != 0 ? 0x30 | (error & 0x0F) : 0x20 | counter;
        }

        int lastCommand() {
            return commands.get(commands.size() - 1);
        }

        void command(int cmd, int hostin) {
            commands.add(cmd);
            if (ignoreCommands) return;
            if (cmd == 0x00) { counter = 0; error = 0; return; }
            if (cmd == 0x01) {
                Arrays.fill(params, 0);
                counter = 0x0F; error = 0; irqEnable = 0;
                return;
            }
            if ((cmd & 0xC0) == 0x80) {
                int p = cmd & 0x3F;
                if (p > 0x2B) { error = 0x11; return; }
                params[p] = hostin;
                resp1 = hostin;
            } else if ((cmd & 0xC0) == 0x40) {
                resp1 = params[cmd & 0x3F];
            } else if (cmd == 0x11) {
                hostout = new int[26];
                int pos = 0;
                for (int ch = 0; ch < 6; ch++) {
                    if ((params[1] & (1 << ch)) == 0) continue;
                    int v = samples[ch];
                    if ((params[0x04 + 4 * ch] & 0x40) != 0) {
                        hostout[pos++] = (v >> 16) & 0xFF;
                        hostout[pos++] = (v >> 8) & 0xFF;
                        hostout[pos++] = v & 0xFF;
                    } else {
                        hostout[pos++] = (v >> 8) & 0xFF;
                        hostout[pos++] = v & 0xFF;
                    }
                }
                irqStatus |= params[1] & irqEnable;
            } else if (cmd != 0x12 && cmd != 0x13) {
                error = 0x10;
                return;
            }
            counter = (counter + 1) & 0x0F;
        }

        @Override
        public void write(byte[] data) throws IOException {
            super.write(data);
            int reg = data[0] & 0xFF;
            if (reg == 0x0A && data.length >= 3) command(data[2] & 0xFF, data[1] & 0xFF);
            else if (reg == 0x0B && data.length >= 2) command(data[1] & 0xFF, 0);
            else if (reg == 0x0F && data.length >= 2) irqEnable = data[1] & 0xFF;
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            super.writeRead(data, n);
            int reg = data[0] & 0xFF;
            int[] block;
            if (reg == 0x00) block = new int[]{partId, 0x03, 0x10};
            else if (reg == 0x10) block = new int[]{resp1};
            else if (reg == 0x11) block = new int[]{resp0()};
            else if (reg == 0x12) { block = new int[]{irqStatus}; irqStatus = 0; }
            else if (reg >= 0x13) block = Arrays.copyOfRange(hostout, reg - 0x13, 26);
            else block = new int[0];
            byte[] out = new byte[n];
            for (int i = 0; i < n && i < block.length; i++) out[i] = (byte) block[i];
            return out;
        }

        @Override
        public InputPin intPin() {
            return pin;
        }
    }

    static class FakePin implements InputPin {
        EdgeHandler handler;
        EdgeTrigger trigger;

        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (h == handler) handler = null; }
        @Override public boolean isHigh() { return true; }
        @Override public void close() {}
    }

    private Mock c;

    private boolean wrote(int... frame) {
        for (byte[] f : c.writes()) {
            if (f.length != frame.length) continue;
            boolean eq = true;
            for (int j = 0; j < f.length; j++) eq &= (f[j] & 0xFF) == frame[j];
            if (eq) return true;
        }
        return false;
    }

    private Si1133Full make() throws IOException {
        c = new Mock();
        Si1133Full s = new Si1133Full(c);
        assertTrue(c.writes().isEmpty(), "constructor touched the bus");
        s.init();
        return s;
    }

    @Test
    void initConfiguresChannelsAndReads() throws IOException {
        Si1133Full s = make();
        assertEquals(0x01, c.commands.get(0));
        assertArrayEquals(new int[]{0x18, 0x09, 0x40}, Arrays.copyOfRange(c.params, 0x02, 0x05));
        assertArrayEquals(new int[]{0x0B, 0x07, 0x40}, Arrays.copyOfRange(c.params, 0x06, 0x09));
        assertEquals(0x03, c.params[0x01]);
        assertEquals(0x3F, c.irqEnable);
        assertTrue(wrote(0x0A, 0x03, 0x81), "PARAM_SET must write HOSTIN0 and COMMAND together");
        assertEquals(new Si1133Minimal.Reading(0x012345, -5), s.read());
        assertEquals(0x11, c.lastCommand());
    }

    @Test
    void wrongPartId() {
        c = new Mock();
        c.partId = 0x32;
        assertThrows(IOException.class, () -> new Si1133Minimal(c).init());
    }

    @Test
    void channelsAndScratchReads() throws IOException {
        Si1133Full s = make();
        s.configureChannel(2, Si1133Full.MUX_LARGE_IR, 3, 2, 1, true, false, 1);
        assertArrayEquals(new int[]{0x22, 0xA3, 0x08}, Arrays.copyOfRange(c.params, 0x0A, 0x0D));
        s.enableChannels(0x07);
        assertEquals(0x07, c.params[0x01]);
        assertArrayEquals(new int[]{0x012345, -5, 0xABCD}, s.readChannels());
        assertArrayEquals(new int[]{0x012345, -5, 0xABCD}, s.readChannels(false));
        assertEquals(new Si1133Minimal.Reading(0x012345, -5), s.read());
        assertThrows(IllegalArgumentException.class, () -> s.configureChannel(6, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> s.configureChannel(0, 0x18, 12));
        assertThrows(IllegalArgumentException.class, () -> s.enableChannels(0x40));
        c.samples[5] = 1234;
        assertEquals(1234, s.readUv());
        assertEquals(0x18, c.params[0x16]);
        assertEquals(0x09, c.params[0x17]);
        assertEquals(0x07, c.params[0x01], "scratch read must restore CHAN_LIST");
        assertEquals(1234, s.readWhite());
        assertEquals(0x0B, c.params[0x16]);
        assertEquals(1234, s.readIr(Si1133Full.MUX_MEDIUM_IR));
        assertEquals(0x01, c.params[0x16]);
        assertEquals(1234, s.readIr());
        assertEquals(0x00, c.params[0x16]);
        assertThrows(IllegalArgumentException.class, () -> s.readIr(0x0B));
        s.force();
        assertEquals(0x11, c.lastCommand());
    }

    @Test
    void autonomousBurstAndThresholds() throws IOException {
        Si1133Full s = make();
        s.startAutonomous(1.0, 2, 3, 4);
        assertEquals(1250, (c.params[0x1A] << 8) | c.params[0x1B]);
        assertArrayEquals(new int[]{2, 3, 4}, Arrays.copyOfRange(c.params, 0x1C, 0x1F));
        assertEquals(0x40, c.params[0x05]);
        assertEquals(0x40, c.params[0x09]);
        assertEquals(0x13, c.lastCommand());
        s.setChannelCounter(2, 3);
        assertEquals(0xC0, c.params[0x0D]);
        s.pause();
        assertEquals(0x12, c.lastCommand());
        assertThrows(IllegalArgumentException.class, () -> s.startAutonomous(0, 1, 1, 1));
        s.setBurst(4);
        assertEquals(0x84, c.params[0x2B]);
        s.setBurst(0);
        assertEquals(0, c.params[0x2B]);
        assertThrows(IllegalArgumentException.class, () -> s.setBurst(128));
        s.setThreshold(1, 0x1234);
        assertEquals(0x12, c.params[0x27]);
        assertEquals(0x34, c.params[0x28]);
        s.setChannelThreshold(0, 2);
        assertEquals(0x42, c.params[0x04]);
        assertThrows(IllegalArgumentException.class, () -> s.setThreshold(3, 0));
    }

    @Test
    void parametersStatusAndReset() throws IOException {
        Si1133Full s = make();
        s.writeParameter(0x1C, 0x55);
        assertEquals(0x55, c.params[0x1C]);
        assertEquals(0x55, s.readParameter(0x1C));
        assertEquals(0x5C, c.lastCommand());
        assertEquals(new Si1133Full.Response(0x20, false, c.counter), s.readResponse());
        assertThrows(IOException.class, () -> s.writeParameter(0x30, 1));
        assertEquals(0x00, c.lastCommand());
        assertEquals(0, c.error);
        c.irqStatus = 0x05;
        assertEquals(0x05, s.dataReady());
        assertEquals(0, s.dataReady());
        assertEquals(new Si1133Full.Id(0x33, 0x03, 0x10), s.readId());
        s.setI2cAddress(0x30);
        assertEquals(0x30, c.params[0x00]);
        assertThrows(IllegalArgumentException.class, () -> s.setI2cAddress(0x80));
        s.reset();
        assertEquals(0x03, c.params[0x01]);
        assertEquals(0x18, c.params[0x02]);
        assertEquals(new Si1133Minimal.Reading(0x012345, -5), s.read());
    }

    @Test
    void interrupt() throws IOException {
        Si1133Full s = make();
        s.disableInterrupt(Si1133Full.SOURCE_CHANNEL1);
        assertEquals(0x3D, c.irqEnable);
        s.enableInterrupt(Si1133Full.SOURCE_CHANNEL1);
        assertEquals(0x3F, c.irqEnable);
        c.irqStatus = 0x02;
        assertEquals(0x02, s.pollInterrupt());
        FakePin pin = new FakePin();
        List<Integer> seen = new ArrayList<>();
        s.onInterrupt(seen::add, pin);
        assertNotNull(pin.handler);
        assertEquals(EdgeTrigger.FALLING, pin.trigger);
        c.irqStatus = 0x01;
        pin.handler.onEdge();
        assertEquals(List.of(0x01), seen);
        s.offInterrupt();
        assertNull(pin.handler);
    }

    @Test
    void commandTimeout() throws IOException {
        c = new Mock();
        Si1133Minimal s = new Si1133Minimal(c);
        s.init();
        c.ignoreCommands = true;
        assertThrows(IOException.class, s::read);
    }
}
