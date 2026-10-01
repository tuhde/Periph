#ifdef __linux__
#include "Discovery.h"
#include "I2CConnectionLinux.h"
#include <linux/i2c-dev.h>
#include <linux/i2c.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <set>
#include <stdexcept>

#ifndef I2C_FUNC_SMBUS_QUICK
#define I2C_FUNC_SMBUS_QUICK 0x00010000
#endif

namespace periph {
namespace discovery {

namespace {

// i2cdetect-compatible policy: EEPROM-class ranges are probed with a read byte.
bool usesReadByte(uint8_t addr) {
    return (addr >= 0x30 && addr <= 0x37) || (addr >= 0x50 && addr <= 0x5F);
}

std::vector<std::string> sortedIds(const std::vector<const ChipEntry*>& chips) {
    std::vector<std::string> ids;
    for (const ChipEntry* c : chips) ids.push_back(c->id);
    std::sort(ids.begin(), ids.end());
    return ids;
}

bool readIdentity(Bus& bus, uint8_t addr, const IdProbe& p, std::map<uint64_t, std::pair<bool, uint32_t>>& cache,
                  uint32_t& value) {
    uint64_t key = (uint64_t)p.reg << 24 | (uint64_t)p.regBytes << 16 | (uint64_t)p.length << 8 | (p.littleEndian ? 1 : 0);
    auto it = cache.find(key);
    if (it == cache.end()) {
        uint8_t buf[4] = {0, 0, 0, 0};
        bool ok = bus.readRegister(addr, p.reg, p.regBytes, p.length, buf);
        uint32_t v = 0;
        if (ok) {
            for (uint8_t i = 0; i < p.length; i++) v = (v << 8) | buf[p.littleEndian ? p.length - 1 - i : i];
        }
        it = cache.emplace(key, std::make_pair(ok, v)).first;
    }
    value = it->second.second;
    return it->second.first;
}

DiscoveredDevice classify(Bus& bus, uint8_t addr, const std::vector<const ChipEntry*>& cands, bool inUse, bool active) {
    DiscoveredDevice dev;
    dev.address = addr;
    dev.inUseByKernel = inUse;
    if (cands.empty()) return dev;
    dev.candidates = sortedIds(cands);
    if (inUse) {
        dev.probeSkipped = ProbeSkipReason::KernelBound;
        return dev;
    }
    std::vector<const ChipEntry*> probed;
    bool writeSensitive = false;
    for (const ChipEntry* c : cands) {
        if (c->probe) probed.push_back(c);
        writeSensitive = writeSensitive || c->writeSensitive;
    }
    if (probed.empty()) return dev;
    if (!active && writeSensitive) {
        dev.probeSkipped = ProbeSkipReason::WriteSensitiveCandidate;
        return dev;
    }

    std::map<uint64_t, std::pair<bool, uint32_t>> cache;
    std::vector<const ChipEntry*> matched;
    for (const ChipEntry* c : probed) {
        uint32_t value = 0;
        if (!readIdentity(bus, addr, *c->probe, cache, value)) continue;
        for (size_t i = 0; i < c->probe->nExpected; i++) {
            if (c->probe->expected[i] == (value & c->probe->mask)) {
                matched.push_back(c);
                break;
            }
        }
    }
    if (matched.size() == 1) {
        dev.candidates = {matched[0]->id};
        dev.identified = matched[0]->id;
        if (matched[0]->driver) dev.driver = matched[0]->driver;
    } else if (matched.size() > 1) {
        dev.candidates = sortedIds(matched);
    } else {
        std::vector<const ChipEntry*> idLess;
        for (const ChipEntry* c : cands)
            if (!c->probe) idLess.push_back(c);
        dev.candidates = sortedIds(idLess);
    }
    return dev;
}

}  // namespace

BusLinux::BusLinux(int bus) : _bus(bus), _fd(-1), _funcs(0) {
    char path[32];
    snprintf(path, sizeof(path), "/dev/i2c-%d", bus);
    _fd = open(path, O_RDWR);
    if (_fd < 0) throw std::runtime_error(std::string("Failed to open ") + path + ": " + strerror(errno));
    if (ioctl(_fd, I2C_FUNCS, &_funcs) < 0) {
        int e = errno;
        close(_fd);
        throw std::runtime_error(std::string("I2C_FUNCS on ") + path + ": " + strerror(e));
    }
}

BusLinux::~BusLinux() {
    if (_fd >= 0) close(_fd);
}

bool BusLinux::quickWriteSupported() const {
    return (_funcs & I2C_FUNC_SMBUS_QUICK) != 0;
}

Probe BusLinux::probe(uint8_t addr, bool readByte) {
    // EBUSY here means a kernel driver already owns the address.
    if (ioctl(_fd, I2C_SLAVE, addr) < 0) {
        if (errno == EBUSY) return Probe::KernelBound;
        return (errno == ENXIO || errno == EREMOTEIO) ? Probe::Absent : Probe::Failed;
    }
    struct i2c_smbus_ioctl_data args;
    union i2c_smbus_data data;
    memset(&args, 0, sizeof(args));
    memset(&data, 0, sizeof(data));
    args.read_write = readByte ? I2C_SMBUS_READ : I2C_SMBUS_WRITE;
    args.command = 0;
    args.size = readByte ? I2C_SMBUS_BYTE : I2C_SMBUS_QUICK;
    args.data = readByte ? &data : nullptr;
    if (ioctl(_fd, I2C_SMBUS, &args) < 0) {
        if (errno == EBUSY) return Probe::KernelBound;
        return (errno == ENXIO || errno == EREMOTEIO) ? Probe::Absent : Probe::Failed;
    }
    return Probe::Present;
}

bool BusLinux::readRegister(uint8_t addr, uint32_t reg, uint8_t regBytes, uint8_t length, uint8_t* out) {
    try {
        I2CConnectionLinux conn(_bus, addr, nullptr, nullptr, regBytes);
        conn.read(reg, out, length);
        return true;
    } catch (const std::exception&) {
        return false;
    }
}

std::map<uint8_t, bool> scanDetailed(Bus& bus, uint8_t first, uint8_t last) {
    std::map<uint8_t, bool> found;
    const bool quick = bus.quickWriteSupported();
    int failures = 0;
    for (int addr = first; addr <= last; addr++) {
        switch (bus.probe((uint8_t)addr, !quick || usesReadByte((uint8_t)addr))) {
            case Probe::Present: found[(uint8_t)addr] = false; break;
            case Probe::KernelBound: found[(uint8_t)addr] = true; break;
            case Probe::Failed: failures++; break;
            case Probe::Absent: break;
        }
    }
    if (failures > 0 && failures == last - first + 1)
        throw std::runtime_error("every probed address failed with a bus error");
    return found;
}

std::vector<uint8_t> scan(Bus& bus) {
    std::vector<uint8_t> out;
    for (const auto& kv : scanDetailed(bus)) out.push_back(kv.first);
    return out;
}

std::vector<uint8_t> scan(int bus) {
    BusLinux b(bus);
    return scan(b);
}

std::vector<DiscoveredDevice> discover(Bus& bus, const ChipEntry* registry, size_t count, bool active) {
    const std::map<uint8_t, bool> present = scanDetailed(bus);
    std::map<uint8_t, std::vector<const ChipEntry*>> byAddr;
    for (size_t i = 0; i < count; i++)
        for (size_t j = 0; j < registry[i].nAddresses; j++) byAddr[registry[i].addresses[j]].push_back(&registry[i]);

    std::vector<DiscoveredDevice> devices;
    std::set<uint8_t> merged;
    for (size_t i = 0; i < count; i++) {
        const ChipEntry& c = registry[i];
        if (!c.aliased) continue;
        bool all = true, busy = false;
        for (size_t j = 0; j < c.nAddresses; j++) {
            auto it = present.find(c.addresses[j]);
            all = all && it != present.end();
            busy = busy || (it != present.end() && it->second);
        }
        if (!all) continue;
        DiscoveredDevice dev;
        dev.address = c.addresses[0];
        // A full block also fits any non-aliased chip that lists every address (24AA025UID).
        std::vector<const ChipEntry*> block = {&c};
        for (size_t k = 0; k < count; k++) {
            const ChipEntry& o = registry[k];
            if (&o == &c || o.aliased) continue;
            bool coversAll = true;
            for (size_t j = 0; j < c.nAddresses; j++) {
                bool found = false;
                for (size_t m = 0; m < o.nAddresses; m++) found = found || o.addresses[m] == c.addresses[j];
                coversAll = coversAll && found;
            }
            if (coversAll) block.push_back(&o);
        }
        dev.candidates = sortedIds(block);
        dev.inUseByKernel = busy;
        for (size_t j = 1; j < c.nAddresses; j++) dev.aliases.push_back(c.addresses[j]);
        devices.push_back(dev);
        for (size_t j = 0; j < c.nAddresses; j++) merged.insert(c.addresses[j]);
    }
    for (const auto& kv : present) {
        if (merged.count(kv.first)) continue;
        auto it = byAddr.find(kv.first);
        static const std::vector<const ChipEntry*> none;
        devices.push_back(classify(bus, kv.first, it == byAddr.end() ? none : it->second, kv.second, active));
    }
    std::sort(devices.begin(), devices.end(),
              [](const DiscoveredDevice& a, const DiscoveredDevice& b) { return a.address < b.address; });
    return devices;
}

std::vector<DiscoveredDevice> discover(Bus& bus, bool active) {
    return discover(bus, kChips, kChipCount, active);
}

std::vector<DiscoveredDevice> discover(int bus, bool active) {
    BusLinux b(bus);
    return discover(b, active);
}

}  // namespace discovery
}  // namespace periph
#endif  // __linux__
