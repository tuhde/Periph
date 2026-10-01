#pragma once
#ifdef __linux__
#include <stddef.h>
#include <stdint.h>
#include <map>
#include <string>
#include <vector>
#include "DiscoveryRegistry.h"

/**
 * @file Discovery.h
 * @brief I²C bus auto-discovery for Linux hosts (specs/feature_i2c_discovery.md).
 *
 * scan() enumerates the addresses that respond on a bus; discover() maps them
 * to the chips in the generated registry (DiscoveryRegistry.h, built from
 * registry/chips.json) and confirms a chip only when an identity-register read
 * matches exactly one candidate. Everything else is reported as candidates.
 * Host-only: not part of the Arduino / Zephyr / ESP-IDF / Pico SDK builds.
 */
namespace periph {
namespace discovery {

/// First address probed by default.
constexpr uint8_t kFirstAddress = 0x08;
/// Last address probed by default.
constexpr uint8_t kLastAddress = 0x77;

/// What one probe of one address told us.
enum class Probe {
    Present,      ///< the device ACKed
    Absent,       ///< NACK: nothing at this address
    KernelBound,  ///< a kernel driver owns the address (EBUSY); counts as present
    Failed        ///< any other bus error
};

/// Why discover() did not run an identity probe on an address.
enum class ProbeSkipReason {
    None,
    KernelBound,             ///< the address is owned by a kernel driver
    WriteSensitiveCandidate  ///< a candidate treats a stray write as data; pass active = true to probe anyway
};

/// One responding address (or merged alias block) and what it could be.
struct DiscoveredDevice {
    uint8_t address = 0;                    ///< 7-bit address (lowest address of an alias block)
    std::vector<std::string> candidates;    ///< registry chip ids that could be here; empty when unknown
    std::string identified;                 ///< chip id confirmed by an identity read, else empty
    std::string driver;                     ///< driver name of the identified chip, if any
    bool inUseByKernel = false;             ///< a kernel driver owns the address (EBUSY)
    ProbeSkipReason probeSkipped = ProbeSkipReason::None;
    std::vector<uint8_t> aliases;           ///< other addresses merged into this device (24AA02UID)
};

/** @brief The minimum a discovery run needs from an I²C bus; tests supply fakes. */
class Bus {
public:
    virtual ~Bus() {}
    /** @brief Probe one address with a quick write, or a read byte when @p readByte is set. */
    virtual Probe probe(uint8_t addr, bool readByte) = 0;
    /** @brief Whether the adapter supports quick write. */
    virtual bool quickWriteSupported() const = 0;
    /**
     * @brief Write the @p regBytes-wide big-endian register address, then read @p length bytes.
     * @return false on any bus error.
     */
    virtual bool readRegister(uint8_t addr, uint32_t reg, uint8_t regBytes, uint8_t length, uint8_t* out) = 0;
};

/** @brief Bus backed by /dev/i2c-N. Throws std::runtime_error if it cannot be opened. */
class BusLinux : public Bus {
public:
    explicit BusLinux(int bus);
    ~BusLinux() override;
    BusLinux(const BusLinux&) = delete;
    BusLinux& operator=(const BusLinux&) = delete;

    Probe probe(uint8_t addr, bool readByte) override;
    bool quickWriteSupported() const override;
    bool readRegister(uint8_t addr, uint32_t reg, uint8_t regBytes, uint8_t length, uint8_t* out) override;

private:
    int _bus;
    int _fd;
    unsigned long _funcs;
};

/**
 * @brief Probe @p first..@p last and report which responding addresses are kernel-bound.
 * @return address -> inUseByKernel for every address that responded.
 * @throws std::runtime_error if every probed address failed with something other than a NACK.
 */
std::map<uint8_t, bool> scanDetailed(Bus& bus, uint8_t first = kFirstAddress, uint8_t last = kLastAddress);

/** @brief Sorted 7-bit addresses that respond on @p bus (0x08-0x77). */
std::vector<uint8_t> scan(Bus& bus);

/** @brief Sorted 7-bit addresses that respond on /dev/i2c-@p bus (0x08-0x77). */
std::vector<uint8_t> scan(int bus);

/**
 * @brief Scan @p bus and name the chips that are connected, using a custom registry.
 *
 * `identified` is set only when an identity-register read matches exactly one
 * chip; otherwise `candidates` lists what the address could be. Addresses with
 * a write-sensitive candidate are not probed unless @p active is true.
 *
 * @return devices sorted by address.
 */
std::vector<DiscoveredDevice> discover(Bus& bus, const ChipEntry* registry, size_t count, bool active = false);

/** @brief discover() with the built-in registry. */
std::vector<DiscoveredDevice> discover(Bus& bus, bool active = false);

/** @brief discover() on /dev/i2c-@p bus with the built-in registry. */
std::vector<DiscoveredDevice> discover(int bus, bool active = false);

}  // namespace discovery
}  // namespace periph
#endif  // __linux__
