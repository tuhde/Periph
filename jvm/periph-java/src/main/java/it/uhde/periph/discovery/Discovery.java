package it.uhde.periph.discovery;

import it.uhde.periph.connection.I2CBus;
import it.uhde.periph.connection.I2CConnection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * I²C bus auto-discovery for Linux hosts (specs/feature_i2c_discovery.md).
 *
 * <p>{@link #scan(int)} enumerates the addresses that respond on a bus;
 * {@link #discover(int, boolean)} maps them to the chips in the generated
 * {@link DiscoveryRegistry} (built from {@code registry/chips.json}) and confirms a chip only
 * when an identity-register read matches exactly one candidate. Everything else is reported
 * as candidates.
 */
public final class Discovery {

    private Discovery() {}

    /** First address probed by default. */
    public static final int FIRST_ADDRESS = 0x08;
    /** Last address probed by default. */
    public static final int LAST_ADDRESS = 0x77;

    // i2cdetect-compatible policy: EEPROM-class ranges are probed with a read byte.
    private static final int[][] READ_BYTE_RANGES = {{0x30, 0x37}, {0x50, 0x5F}};

    /** Why an identity probe was not run. */
    public enum ProbeSkipReason {
        /** The address is owned by a kernel driver. */
        KERNEL_BOUND,
        /** A candidate treats a stray write as data; pass {@code active = true} to probe anyway. */
        WRITE_SENSITIVE_CANDIDATE
    }

    /**
     * One responding address (or merged alias block) and what it could be.
     *
     * @param address            7-bit address (lowest address of an alias block)
     * @param candidates         registry chip ids that could be here; empty when unknown to the registry
     * @param identified         chip id confirmed by an identity read, else {@code null}
     * @param driver             driver name of the identified chip, or {@code null}
     * @param inUseByKernel      true when a kernel driver owns the address
     * @param probeSkippedReason set when an identity probe was not run, else {@code null}
     * @param aliases            other addresses merged into this device (24AA02UID), else empty
     */
    public record DiscoveredDevice(int address, List<String> candidates, String identified, String driver,
                                   boolean inUseByKernel, ProbeSkipReason probeSkippedReason,
                                   List<Integer> aliases) {}

    /** The minimum a discovery run needs from an I²C bus; tests supply fakes. */
    public interface Bus {
        /** Probe one address with a quick write, or a read byte when {@code readByte} is set. */
        I2CBus.Probe probe(int address, boolean readByte) throws IOException;

        /** @return whether the adapter supports quick write */
        boolean quickWriteSupported();

        /** Write the {@code regBytes}-wide big-endian register address, then read {@code length} bytes. */
        byte[] readRegister(int address, int register, int regBytes, int length) throws IOException;
    }

    private record LinuxBus(I2CBus bus) implements Bus {
        @Override public I2CBus.Probe probe(int address, boolean readByte) throws IOException {
            return bus.probe(address, readByte);
        }
        @Override public boolean quickWriteSupported() { return bus.quickWriteSupported(); }
        @Override public byte[] readRegister(int address, int register, int regBytes, int length) throws IOException {
            try (var conn = new I2CConnection(bus.bus(), address, null, null, regBytes)) {
                return conn.read(register, length);
            }
        }
    }

    private static boolean usesReadByte(int address) {
        for (int[] r : READ_BYTE_RANGES) if (address >= r[0] && address <= r[1]) return true;
        return false;
    }

    /**
     * Probe {@code first..last} and report which responding addresses are kernel-bound.
     *
     * @param bus   the bus
     * @param first first address to probe
     * @param last  last address to probe
     * @return address to in-use-by-kernel for every address that responded, sorted by address
     * @throws IOException if every probed address failed with something other than a NACK
     */
    public static Map<Integer, Boolean> scanDetailed(Bus bus, int first, int last) throws IOException {
        Map<Integer, Boolean> found = new TreeMap<>();
        boolean quick = bus.quickWriteSupported();
        int failures = 0;
        for (int addr = first; addr <= last; addr++) {
            switch (bus.probe(addr, !quick || usesReadByte(addr))) {
                case PRESENT -> found.put(addr, false);
                case KERNEL_BOUND -> found.put(addr, true);
                case FAILED -> failures++;
                case ABSENT -> { }
            }
        }
        if (failures > 0 && failures == last - first + 1) {
            throw new IOException("every probed address failed with a bus error");
        }
        return found;
    }

    /**
     * Sorted 7-bit addresses that respond on {@code /dev/i2c-<bus>} (0x08-0x77).
     *
     * @param bus bus number
     * @return responding addresses
     * @throws IOException on a bus-level failure
     */
    public static int[] scan(int bus) throws IOException {
        try (var b = I2CBus.open(bus)) {
            return scanDetailed(new LinuxBus(b), FIRST_ADDRESS, LAST_ADDRESS).keySet().stream().mapToInt(Integer::intValue).toArray();
        }
    }

    private static String probeKey(DiscoveryRegistry.IdProbe p) {
        return p.register() + ":" + p.regBytes() + ":" + p.length() + ":" + p.littleEndian();
    }

    private static Long readIdentity(Bus bus, int addr, DiscoveryRegistry.IdProbe p, Map<String, Long> cache,
                                     Set<String> failed) {
        String key = probeKey(p);
        if (!cache.containsKey(key) && !failed.contains(key)) {
            try {
                byte[] data = bus.readRegister(addr, p.register(), p.regBytes(), p.length());
                if (data.length != p.length()) throw new IOException("short read");
                long v = 0;
                for (int i = 0; i < p.length(); i++) {
                    v = (v << 8) | (data[p.littleEndian() ? p.length() - 1 - i : i] & 0xFFL);
                }
                cache.put(key, v);
            } catch (IOException e) {
                failed.add(key);
            }
        }
        return cache.get(key);
    }

    private static List<String> sortedIds(List<DiscoveryRegistry.Chip> chips) {
        return chips.stream().map(DiscoveryRegistry.Chip::id).sorted().toList();
    }

    private static DiscoveredDevice device(int address, List<String> candidates, String identified, String driver,
                                           boolean inUse, ProbeSkipReason reason, List<Integer> aliases) {
        return new DiscoveredDevice(address, candidates, identified, driver, inUse, reason, aliases);
    }

    private static DiscoveredDevice classify(Bus bus, int addr, List<DiscoveryRegistry.Chip> cands, boolean inUse,
                                             boolean active) {
        if (cands.isEmpty()) return device(addr, List.of(), null, null, inUse, null, List.of());
        List<String> ids = sortedIds(cands);
        if (inUse) return device(addr, ids, null, null, true, ProbeSkipReason.KERNEL_BOUND, List.of());
        List<DiscoveryRegistry.Chip> probed = cands.stream().filter(c -> c.probe() != null).toList();
        if (probed.isEmpty()) return device(addr, ids, null, null, false, null, List.of());
        if (!active && cands.stream().anyMatch(DiscoveryRegistry.Chip::writeSensitive)) {
            return device(addr, ids, null, null, false, ProbeSkipReason.WRITE_SENSITIVE_CANDIDATE, List.of());
        }

        Map<String, Long> cache = new HashMap<>();
        Set<String> failed = new HashSet<>();
        List<DiscoveryRegistry.Chip> matched = new ArrayList<>();
        for (var chip : probed) {
            var p = chip.probe();
            Long value = readIdentity(bus, addr, p, cache, failed);
            if (value != null && Arrays.stream(p.expected()).anyMatch(e -> e == (value & p.mask()))) matched.add(chip);
        }
        if (matched.size() == 1) {
            var chip = matched.get(0);
            return device(addr, List.of(chip.id()), chip.id(), chip.driver(), false, null, List.of());
        }
        if (matched.size() > 1) return device(addr, sortedIds(matched), null, null, false, null, List.of());
        return device(addr, sortedIds(cands.stream().filter(c -> c.probe() == null).toList()), null, null, false, null, List.of());
    }

    /**
     * Scan {@code bus} and name the chips that are connected, using {@code registry}.
     *
     * <p>{@code identified} is set only when an identity-register read matches exactly one chip;
     * otherwise {@code candidates} lists what the address could be. Addresses with a
     * write-sensitive candidate are not probed unless {@code active} is true.
     *
     * @param bus      the bus
     * @param registry chip table in the {@link DiscoveryRegistry#CHIPS} format
     * @param active   also probe addresses that have write-sensitive candidates
     * @return devices sorted by address
     * @throws IOException on a bus-level failure
     */
    public static List<DiscoveredDevice> discover(Bus bus, DiscoveryRegistry.Chip[] registry, boolean active)
            throws IOException {
        Map<Integer, Boolean> present = scanDetailed(bus, FIRST_ADDRESS, LAST_ADDRESS);
        Map<Integer, List<DiscoveryRegistry.Chip>> byAddr = new HashMap<>();
        for (var chip : registry) {
            for (int a : chip.addresses()) byAddr.computeIfAbsent(a, k -> new ArrayList<>()).add(chip);
        }

        List<DiscoveredDevice> devices = new ArrayList<>();
        Set<Integer> merged = new HashSet<>();
        for (var chip : registry) {
            if (!chip.aliased()) continue;
            if (Arrays.stream(chip.addresses()).allMatch(present::containsKey)) {
                boolean busy = Arrays.stream(chip.addresses()).anyMatch(present::get);
                List<Integer> aliases = Arrays.stream(chip.addresses()).skip(1).boxed().toList();
                // A full block also fits any non-aliased chip that lists every address (24AA025UID).
                List<DiscoveryRegistry.Chip> block = new ArrayList<>(List.of(chip));
                for (var o : registry) {
                    if (o != chip && !o.aliased() && Arrays.stream(chip.addresses()).allMatch(a -> Arrays.stream(o.addresses()).anyMatch(x -> x == a))) {
                        block.add(o);
                    }
                }
                devices.add(device(chip.addresses()[0], sortedIds(block), null, null, busy, null, aliases));
                for (int a : chip.addresses()) merged.add(a);
            }
        }
        for (var e : present.entrySet()) {
            if (!merged.contains(e.getKey())) {
                devices.add(classify(bus, e.getKey(), byAddr.getOrDefault(e.getKey(), List.of()), e.getValue(), active));
            }
        }
        devices.sort((a, b) -> Integer.compare(a.address(), b.address()));
        return devices;
    }

    /**
     * Scan {@code /dev/i2c-<bus>} and name the chips that are connected, using the built-in registry.
     *
     * @param bus    bus number
     * @param active also probe addresses that have write-sensitive candidates
     * @return devices sorted by address
     * @throws IOException on a bus-level failure
     */
    public static List<DiscoveredDevice> discover(int bus, boolean active) throws IOException {
        try (var b = I2CBus.open(bus)) {
            return discover(new LinuxBus(b), DiscoveryRegistry.CHIPS, active);
        }
    }
}
