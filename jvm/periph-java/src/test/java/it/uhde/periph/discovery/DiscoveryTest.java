package it.uhde.periph.discovery;

import it.uhde.periph.connection.I2CBus;
import it.uhde.periph.discovery.Discovery.DiscoveredDevice;
import it.uhde.periph.discovery.Discovery.ProbeSkipReason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryTest {

    /** Fake bus: devices maps address -> {register: byte}; missing registers read 0xFF. */
    static final class FakeBus implements Discovery.Bus {
        final Map<Integer, Map<Integer, Integer>> devices = new HashMap<>();
        final Set<Integer> busy = new HashSet<>();
        boolean noQuick, broken, readError;
        final List<int[]> probes = new ArrayList<>();   // {address, readByte ? 1 : 0}
        final List<int[]> registerReads = new ArrayList<>(); // {address, register, regBytes}

        FakeBus with(int addr, int... regValuePairs) {
            Map<Integer, Integer> regs = new HashMap<>();
            for (int i = 0; i < regValuePairs.length; i += 2) regs.put(regValuePairs[i], regValuePairs[i + 1]);
            devices.put(addr, regs);
            return this;
        }

        @Override public I2CBus.Probe probe(int address, boolean readByte) {
            probes.add(new int[]{address, readByte ? 1 : 0});
            if (broken) return I2CBus.Probe.FAILED;
            if (busy.contains(address)) return I2CBus.Probe.KERNEL_BOUND;
            return devices.containsKey(address) ? I2CBus.Probe.PRESENT : I2CBus.Probe.ABSENT;
        }

        @Override public boolean quickWriteSupported() { return !noQuick; }

        @Override public byte[] readRegister(int address, int register, int regBytes, int length) throws IOException {
            if (readError) throw new IOException("io error");
            var regs = devices.get(address);
            if (regs == null) throw new IOException("no ack");
            registerReads.add(new int[]{address, register, regBytes});
            byte[] out = new byte[length];
            for (int i = 0; i < length; i++) out[i] = (byte) (int) regs.getOrDefault(register + i, 0xFF);
            return out;
        }
    }

    private static DiscoveredDevice only(FakeBus bus, boolean active) throws IOException {
        return Discovery.discover(bus, DiscoveryRegistry.CHIPS, active).get(0);
    }

    private static DiscoveredDevice one(int addr, int... regs) throws IOException {
        return only(new FakeBus().with(addr, regs), false);
    }

    @Test
    void scanFindsAllAndPicksProbeMethod() throws IOException {
        var bus = new FakeBus().with(0x76).with(0x50).with(0x1B);
        assertEquals(List.of(0x1B, 0x50, 0x76), List.copyOf(Discovery.scanDetailed(bus, 0x08, 0x77).keySet()));
        Map<Integer, Boolean> read = new HashMap<>();
        for (int[] p : bus.probes) read.put(p[0], p[1] == 1);
        assertFalse(read.get(0x76));
        assertFalse(read.get(0x08));
        assertTrue(read.get(0x50));
        assertTrue(read.get(0x30));
        assertTrue(read.get(0x5F));
        assertEquals(0x08, bus.probes.get(0)[0]);
        assertEquals(0x77, bus.probes.get(bus.probes.size() - 1)[0]);
    }

    @Test
    void scanFallsBackToReadByteWithoutQuickWrite() throws IOException {
        var bus = new FakeBus().with(0x76);
        bus.noQuick = true;
        Discovery.scanDetailed(bus, 0x08, 0x77);
        assertTrue(bus.probes.stream().allMatch(p -> p[1] == 1));
    }

    @Test
    void scanEbusyIsPresentAndFlagged() throws IOException {
        var bus = new FakeBus().with(0x40);
        bus.busy.add(0x42);
        assertEquals(Map.of(0x40, false, 0x42, true), Discovery.scanDetailed(bus, 0x08, 0x77));
    }

    @Test
    void scanThrowsWhenEveryAddressFails() throws IOException {
        var bus = new FakeBus();
        bus.broken = true;
        assertThrows(IOException.class, () -> Discovery.scanDetailed(bus, 0x08, 0x77));
        assertTrue(Discovery.scanDetailed(new FakeBus(), 0x08, 0x77).isEmpty());
    }

    @Test
    void identifiesChipsBySingleByteRegister() throws IOException {
        Object[][] table = {
            {"bme280", 0xD0, 0x60, 0x76}, {"bmp280", 0xD0, 0x58, 0x76}, {"bme680", 0xD0, 0x61, 0x77},
            {"bmp384", 0x00, 0x50, 0x76}, {"mpu6050", 0x75, 0x68, 0x68}, {"mpu9250", 0x75, 0x71, 0x68},
            {"mpu9255", 0x75, 0x73, 0x69}, {"l3g4200d", 0x0F, 0xD3, 0x68}, {"lps33hw", 0x0F, 0xB1, 0x5C},
            {"adxl345", 0x00, 0xE5, 0x53}, {"vl53l0x", 0xC0, 0xEE, 0x29}, {"mfrc522", 0x37, 0x92, 0x28},
        };
        for (Object[] row : table) {
            // 0x28-0x2F also hosts the write-sensitive DS1881, so those need active probing.
            int addr = (int) row[3];
            var d = only(new FakeBus().with(addr, (int) row[1], (int) row[2]), addr == 0x28 || addr == 0x29);
            assertEquals(row[0], d.identified(), (String) row[0]);
        }
        assertEquals("bme280", one(0x76, 0xD0, 0x60).driver());
    }

    @Test
    void identifiesMultiByteAndMaskedIdentityRegisters() throws IOException {
        assertEquals("ens160", one(0x52, 0x00, 0x60, 0x01, 0x01).identified());
        assertEquals("ina226", one(0x40, 0xFF, 0x22, 0x100, 0x60).identified());
        assertEquals("ina3221", one(0x40, 0xFF, 0x32, 0x100, 0x20).identified());
        assertEquals("mcp9808", one(0x18, 0x07, 0x04, 0x08, 0x01).identified());
        var bus = new FakeBus().with(0x29, 0x010F, 0xEA, 0x0110, 0xCC);
        assertEquals("vl53l1x", only(bus, true).identified());
        assertTrue(bus.registerReads.stream().anyMatch(r -> r[2] == 2), "register address sent as two bytes");
    }

    @Test
    void ds1881WriteSensitiveBlocksProbeAt0x29() throws IOException {
        var bus = new FakeBus().with(0x29, 0xC0, 0xEE);
        var d = only(bus, false);
        assertNull(d.identified());
        assertEquals(ProbeSkipReason.WRITE_SENSITIVE_CANDIDATE, d.probeSkippedReason());
        assertTrue(d.candidates().contains("ds1881"));
        assertTrue(bus.registerReads.isEmpty());
    }

    @Test
    void hmc5883lAndLsm303MagAreAmbiguous() throws IOException {
        // Both report the identity 0x483433 (registry/known_ambiguities.json).
        var d = one(0x1E, 0x0A, 0x48, 0x0B, 0x34, 0x0C, 0x33);
        assertNull(d.identified());
        assertEquals(List.of("hmc5883l", "lsm303-mag"), d.candidates());
    }

    @Test
    void writeSensitiveCandidatesBlockProbingUnlessActive() throws IOException {
        var bus = new FakeBus().with(0x48, 0x0F, 0x01, 0x10, 0x17);
        var d = only(bus, false);
        assertNull(d.identified());
        assertEquals(ProbeSkipReason.WRITE_SENSITIVE_CANDIDATE, d.probeSkippedReason());
        assertTrue(bus.registerReads.isEmpty());
        assertEquals("tmp117", only(new FakeBus().with(0x48, 0x0F, 0x01, 0x10, 0x17), true).identified());

        assertEquals(ProbeSkipReason.WRITE_SENSITIVE_CANDIDATE, one(0x39, 0x92, 0xAB).probeSkippedReason());
        assertEquals("apds9960", only(new FakeBus().with(0x39, 0x92, 0xAB), true).identified());
        assertEquals("apds-9930", only(new FakeBus().with(0x39, 0x92, 0x39), true).identified());
    }

    @Test
    void ambiguityIsAFinalAnswer() throws IOException {
        var d = one(0x5C, 0x0F, 0xB4);
        assertNull(d.identified());
        assertEquals(List.of("lps22df", "lps28dfw"), d.candidates());
        d = one(0x77, 0xD0, 0x55);
        assertNull(d.identified());
        assertEquals(List.of("bmp085", "bmp180"), d.candidates());
    }

    @Test
    void fallsBackToIdLessCandidates() throws IOException {
        assertEquals(List.of("drv8830", "ds3231", "pcf8523"), one(0x68).candidates());
        assertEquals(List.of("ina219"), one(0x40).candidates());
        var d = one(0x36);
        assertNull(d.identified());
        assertEquals(List.of("as5600"), d.candidates());
        d = one(0x0B);
        assertTrue(d.candidates().isEmpty());
        assertNull(d.identified());
        var bus = new FakeBus().with(0x38);
        d = only(bus, false);
        assertEquals(List.of("ade7953", "aht21", "bma150", "pcf8574", "pcf8576"), d.candidates());
        assertEquals(ProbeSkipReason.WRITE_SENSITIVE_CANDIDATE, d.probeSkippedReason());
        assertTrue(bus.registerReads.isEmpty());
    }

    @Test
    void customRegistryAndActiveFlag() throws IOException {
        var registry = new DiscoveryRegistry.Chip[]{
            new DiscoveryRegistry.Chip("pcf-like", null, true, false, new int[]{0x20}, null),
            new DiscoveryRegistry.Chip("idchip", null, false, false, new int[]{0x20},
                new DiscoveryRegistry.IdProbe(0x10, 1, 1, false, 0xFF, new long[]{0x42})),
        };
        var bus = new FakeBus().with(0x20, 0x10, 0x42);
        var d = Discovery.discover(bus, registry, false).get(0);
        assertEquals(ProbeSkipReason.WRITE_SENSITIVE_CANDIDATE, d.probeSkippedReason());
        assertEquals(List.of("idchip", "pcf-like"), d.candidates());
        assertTrue(bus.registerReads.isEmpty());
        bus = new FakeBus().with(0x20, 0x10, 0x42);
        d = Discovery.discover(bus, registry, true).get(0);
        assertEquals("idchip", d.identified());
        assertFalse(bus.registerReads.isEmpty());
    }

    @Test
    void kernelBoundAddressIsReportedNotProbed() throws IOException {
        var bus = new FakeBus().with(0x76, 0xD0, 0x60);
        bus.busy.add(0x77);
        var d = Discovery.discover(bus, DiscoveryRegistry.CHIPS, false).stream().filter(x -> x.address() == 0x77).findFirst().orElseThrow();
        assertTrue(d.inUseByKernel());
        assertEquals(ProbeSkipReason.KERNEL_BOUND, d.probeSkippedReason());
        assertNull(d.identified());
        assertTrue(bus.registerReads.stream().noneMatch(r -> r[0] == 0x77));
    }

    @Test
    void failedIdentityReadIsNoMatch() throws IOException {
        var bus = new FakeBus().with(0x76, 0xD0, 0x60);
        bus.readError = true;
        var d = only(bus, false);
        assertNull(d.identified());
        assertTrue(d.candidates().isEmpty());
    }

    @Test
    void aliasedBlockMergedOnlyWhenComplete() throws IOException {
        var bus = new FakeBus();
        for (int a = 0x50; a <= 0x57; a++) bus.with(a);
        var devs = Discovery.discover(bus, DiscoveryRegistry.CHIPS, false);
        assertEquals(1, devs.size());
        assertEquals(0x50, devs.get(0).address());
        assertEquals(List.of("24aa025uid", "24aa02uid", "mb85rc"), devs.get(0).candidates());
        assertEquals(List.of(0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57), devs.get(0).aliases());
        devs = Discovery.discover(new FakeBus().with(0x50).with(0x51), DiscoveryRegistry.CHIPS, false);
        assertEquals(List.of(0x50, 0x51), devs.stream().map(DiscoveredDevice::address).toList());
        assertTrue(devs.stream().allMatch(x -> x.aliases().isEmpty()));
    }

    @Test
    void registryIsSane() {
        assertTrue(DiscoveryRegistry.CHIPS.length >= 45);
        assertEquals(DiscoveryRegistry.CHIPS.length,
            Arrays.stream(DiscoveryRegistry.CHIPS).map(DiscoveryRegistry.Chip::id).distinct().count());
    }
}
