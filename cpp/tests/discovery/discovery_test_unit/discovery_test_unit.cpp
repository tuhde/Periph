#include <stdio.h>
#include <errno.h>
#include <algorithm>
#include <map>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>
#include "Discovery.h"

using namespace periph::discovery;

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else      { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<std::string> Ids;
typedef std::map<uint32_t, uint8_t> Regs;

// Fake bus: devices maps address -> {register: byte}; missing registers read 0xFF.
class FakeBus : public Bus {
public:
    std::map<uint8_t, Regs> devices;
    std::set<uint8_t> busy;
    bool noQuick = false, broken = false, readError = false;
    std::vector<std::pair<uint8_t, bool>> probes;               // {address, readByte}
    std::vector<std::vector<uint32_t>> registerReads;           // {address, register, regBytes}

    FakeBus& with(uint8_t addr, Regs regs = Regs()) { devices[addr] = regs; return *this; }

    Probe probe(uint8_t addr, bool readByte) override {
        probes.push_back({addr, readByte});
        if (broken) return Probe::Failed;
        if (busy.count(addr)) return Probe::KernelBound;
        return devices.count(addr) ? Probe::Present : Probe::Absent;
    }
    bool quickWriteSupported() const override { return !noQuick; }
    bool readRegister(uint8_t addr, uint32_t reg, uint8_t regBytes, uint8_t length, uint8_t* out) override {
        if (readError || !devices.count(addr)) return false;
        registerReads.push_back({addr, reg, regBytes});
        for (uint8_t i = 0; i < length; i++) {
            auto it = devices[addr].find(reg + i);
            out[i] = it == devices[addr].end() ? 0xFF : it->second;
        }
        return true;
    }
};

static DiscoveredDevice only(FakeBus& bus, bool active = false) { return discover(bus, active).at(0); }
static DiscoveredDevice one(uint8_t addr, Regs regs = Regs(), bool active = false) { FakeBus b; b.with(addr, regs); return only(b, active); }

int main() {
    // --- scan: method per address ---
    {
        FakeBus bus; bus.with(0x76).with(0x50).with(0x1B);
        std::vector<uint8_t> got = scan(bus);
        check_true(got == std::vector<uint8_t>({0x1B, 0x50, 0x76}), "scan_finds_all");
        std::map<uint8_t, bool> read;
        for (auto& p : bus.probes) read[p.first] = p.second;
        check_true(!read[0x76] && !read[0x08], "scan_quick_write_default");
        check_true(read[0x50] && read[0x30] && read[0x5F], "scan_read_byte_eeprom_ranges");
        check_true(bus.probes.front().first == 0x08 && bus.probes.back().first == 0x77, "scan_skips_reserved");
    }
    {
        FakeBus bus; bus.with(0x76); bus.noQuick = true; scan(bus);
        bool allRead = true;
        for (auto& p : bus.probes) allRead = allRead && p.second;
        check_true(allRead, "scan_fallback_read_byte_without_quick");
    }
    {
        FakeBus bus; bus.with(0x40); bus.busy.insert(0x42);
        std::map<uint8_t, bool> d = scanDetailed(bus);
        check_true(d.size() == 2 && !d[0x40] && d[0x42], "scan_ebusy_is_present");
    }
    {
        FakeBus bus; bus.broken = true;
        bool threw = false;
        try { scan(bus); } catch (const std::runtime_error&) { threw = true; }
        check_true(threw, "scan_throws_on_bus_failure");
        FakeBus empty;
        check_true(scan(empty).empty(), "scan_empty_bus");
    }

    // --- identity probes ---
    {
        DiscoveredDevice d = one(0x76, {{0xD0, 0x60}});
        check_true(d.identified == "bme280" && d.candidates == Ids({"bme280"}) && d.driver == "bme280", "bme280_identified");
    }
    struct Row { const char* id; uint32_t reg; uint8_t value; uint8_t addr; };
    const Row rows[] = {
        {"bmp280", 0xD0, 0x58, 0x76}, {"bme680", 0xD0, 0x61, 0x77}, {"bmp384", 0x00, 0x50, 0x76},
        {"mpu6050", 0x75, 0x68, 0x68}, {"mpu9250", 0x75, 0x71, 0x68}, {"mpu9255", 0x75, 0x73, 0x69},
        {"l3g4200d", 0x0F, 0xD3, 0x68}, {"lps33hw", 0x0F, 0xB1, 0x5C}, {"adxl345", 0x00, 0xE5, 0x53},
        {"vl53l0x", 0xC0, 0xEE, 0x29}, {"mfrc522", 0x37, 0x92, 0x28},
    };
    for (const Row& r : rows) {
        // 0x28-0x2F also hosts the write-sensitive DS1881, so those need active probing.
        DiscoveredDevice d = one(r.addr, {{r.reg, r.value}}, r.addr == 0x28 || r.addr == 0x29);
        check_true(d.identified == r.id, (std::string("identify_") + r.id).c_str());
    }
    check_true(one(0x52, {{0x00, 0x60}, {0x01, 0x01}}).identified == "ens160", "identify_ens160_little_endian");
    check_true(one(0x40, {{0xFF, 0x22}, {0x100, 0x60}}).identified == "ina226", "identify_ina226_die_id");
    check_true(one(0x40, {{0xFF, 0x32}, {0x100, 0x20}}).identified == "ina3221", "identify_ina3221_die_id");
    check_true(one(0x18, {{0x07, 0x04}, {0x08, 0x01}}).identified == "mcp9808", "identify_mcp9808_masked");
    {
        // HMC5883L and the LSM303 magnetometer share the identity 0x483433 (known ambiguity).
        DiscoveredDevice d = one(0x1E, {{0x0A, 0x48}, {0x0B, 0x34}, {0x0C, 0x33}});
        check_true(d.identified.empty() && d.candidates == Ids({"hmc5883l", "lsm303-mag"}), "hmc5883l_lsm303_mag_ambiguous");
    }
    {
        // 0x28-0x2F hosts the DS1881, whose command bytes can change a wiper: no probing without active.
        FakeBus bus; bus.with(0x29, {{0xC0, 0xEE}});
        DiscoveredDevice d = only(bus);
        bool hasDs1881 = false;
        for (auto& c : d.candidates) hasDs1881 = hasDs1881 || c == "ds1881";
        check_true(d.identified.empty() && d.probeSkipped == ProbeSkipReason::WriteSensitiveCandidate && hasDs1881 &&
                   bus.registerReads.empty(), "ds1881_write_sensitive_blocks_probe_at_0x29");
    }
    {
        FakeBus bus; bus.with(0x29, {{0x010F, 0xEA}, {0x0110, 0xCC}});
        check_true(only(bus, true).identified == "vl53l1x", "identify_vl53l1x_two_byte_register");
        bool wide = false;
        for (auto& r : bus.registerReads) wide = wide || r[2] == 2;
        check_true(wide, "vl53l1x_register_sent_as_two_bytes");
    }
    {
        FakeBus bus; bus.with(0x48, {{0x0F, 0x01}, {0x10, 0x17}});
        DiscoveredDevice d = only(bus);
        check_true(d.identified.empty() && d.probeSkipped == ProbeSkipReason::WriteSensitiveCandidate && bus.registerReads.empty(),
                   "tmp117_skipped_pcf8591_write_sensitive");
        FakeBus bus2; bus2.with(0x48, {{0x0F, 0x01}, {0x10, 0x17}});
        check_true(only(bus2, true).identified == "tmp117", "identify_tmp117_masked");
        FakeBus a; a.with(0x39, {{0x92, 0xAB}});
        check_true(only(a).probeSkipped == ProbeSkipReason::WriteSensitiveCandidate, "apds_skipped_without_active");
        FakeBus b; b.with(0x39, {{0x92, 0xAB}});
        check_true(only(b, true).identified == "apds9960", "identify_apds9960_active");
        FakeBus c; c.with(0x39, {{0x92, 0x39}});
        check_true(only(c, true).identified == "apds-9930", "identify_apds_9930_active");
    }

    // --- ambiguity is a final answer ---
    {
        DiscoveredDevice d = one(0x5C, {{0x0F, 0xB4}});
        check_true(d.identified.empty() && d.candidates == Ids({"lps22df", "lps28dfw"}), "lps22df_lps28dfw_ambiguous");
        d = one(0x77, {{0xD0, 0x55}});
        check_true(d.identified.empty() && d.candidates == Ids({"bmp085", "bmp180"}), "bmp085_bmp180_ambiguous");
    }

    // --- no ID match falls back to ID-less candidates ---
    {
        DiscoveredDevice d = one(0x68);
        check_true(d.identified.empty() && d.candidates == Ids({"drv8830", "ds3231", "pcf8523"}), "ds3231_pcf8523_remain");
        check_true(one(0x40).candidates == Ids({"ina219"}), "ina219_by_elimination");
        d = one(0x36);
        check_true(d.identified.empty() && d.candidates == Ids({"as5600"}), "as5600_sole_candidate_unconfirmed");
        d = one(0x0B);
        check_true(d.candidates.empty() && d.identified.empty(), "unknown_device");
        FakeBus bus; bus.with(0x38);
        d = only(bus);
        check_true(d.candidates == Ids({"ade7953", "aht21", "bma150", "pcf8574", "pcf8576"}), "aht21_cands");
        check_true(d.probeSkipped == ProbeSkipReason::WriteSensitiveCandidate && bus.registerReads.empty(), "no_probe_when_write_sensitive_candidate");
    }

    // --- custom registry and active flag ---
    {
        static const uint8_t addr[] = {0x20};
        static const uint32_t exp[] = {0x42};
        static const IdProbe probe = {0x10, 1, 1, false, 0xFF, exp, 1};
        static const ChipEntry registry[] = {
            {"pcf-like", nullptr, true, false, addr, 1, nullptr},
            {"idchip", nullptr, false, false, addr, 1, &probe},
        };
        FakeBus bus; bus.with(0x20, {{0x10, 0x42}});
        DiscoveredDevice d = discover(bus, registry, 2, false).at(0);
        check_true(d.probeSkipped == ProbeSkipReason::WriteSensitiveCandidate && bus.registerReads.empty() &&
                   d.candidates == Ids({"idchip", "pcf-like"}) && d.identified.empty(), "write_sensitive_skips_probe");
        FakeBus bus2; bus2.with(0x20, {{0x10, 0x42}});
        d = discover(bus2, registry, 2, true).at(0);
        check_true(d.identified == "idchip" && !bus2.registerReads.empty(), "active_probes_anyway");
    }

    // --- kernel-bound ---
    {
        FakeBus bus; bus.with(0x76, {{0xD0, 0x60}}); bus.busy.insert(0x77);
        DiscoveredDevice d;
        for (auto& x : discover(bus)) if (x.address == 0x77) d = x;
        check_true(d.inUseByKernel && d.probeSkipped == ProbeSkipReason::KernelBound && d.identified.empty(), "kernel_bound_reported");
        bool probed = false;
        for (auto& r : bus.registerReads) probed = probed || r[0] == 0x77;
        check_true(!probed, "kernel_bound_not_probed");
    }

    // --- failed identity read ---
    {
        FakeBus bus; bus.with(0x76, {{0xD0, 0x60}}); bus.readError = true;
        DiscoveredDevice d = only(bus);
        check_true(d.identified.empty() && d.candidates.empty(), "failed_read_is_no_match");
    }

    // --- aliased 24AA02UID ---
    {
        FakeBus bus;
        for (uint8_t a = 0x50; a <= 0x57; a++) bus.with(a);
        std::vector<DiscoveredDevice> devs = discover(bus);
        check_true(devs.size() == 1 && devs[0].address == 0x50 && devs[0].candidates == Ids({"24aa025uid", "24aa02uid", "mb85rc"}) &&
                   devs[0].aliases == std::vector<uint8_t>({0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57}), "alias_block_merged");
        FakeBus partial; partial.with(0x50).with(0x51);
        devs = discover(partial);
        check_true(devs.size() == 2 && devs[0].aliases.empty() && devs[1].aliases.empty(), "partial_alias_reported_individually");
    }

    // --- registry sanity ---
    {
        std::set<std::string> ids;
        for (size_t i = 0; i < kChipCount; i++) ids.insert(kChips[i].id);
        check_true(kChipCount >= 45 && ids.size() == kChipCount, "registry_sane");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
