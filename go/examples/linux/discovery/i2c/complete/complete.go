//go:build linux && !tinygo

// I²C discovery complete example — Linux host.
//
// Exercises every public function: Scan, ScanBus/ScanDetailed over an opened
// bus, and Discover with and without the active flag.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/discovery"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	addresses, err := discovery.Scan(bus) // Scan bus, (bus=1) → ([]uint8 7-bit addresses, error)
	// zero-length quick write per address, read byte on 0x30-0x37 / 0x50-0x5F; EBUSY counts as present
	if err != nil {
		panic(err)
	}
	fmt.Printf("%#02x\n", addresses)

	b, err := discovery.Open(bus) // Open bus, (bus=1) → (*LinuxBus, error)
	// opens /dev/i2c-N once so several scans share one file descriptor
	if err != nil {
		panic(err)
	}
	defer b.Close()

	detail, err := discovery.ScanDetailed(b, discovery.FirstAddress, discovery.LastAddress) // Scan with kernel-binding info, (bus, first=0x08, last=0x77) → (map[uint8]bool, error)
	// value is true when a kernel driver owns the address (i2cdetect "UU")
	if err != nil {
		panic(err)
	}
	fmt.Println(detail)

	devices, err := discovery.Discover(bus, false) // Discover chips, (bus=1, active=false) → ([]Device, error)
	// identity reads confirm a chip only when exactly one candidate matches
	if err != nil {
		panic(err)
	}
	for _, d := range devices {
		fmt.Printf("%+v\n", d)
	}

	devices, err = discovery.Discover(bus, true) // Discover chips incl. write-sensitive addresses, (bus=1, active=true) → ([]Device, error)
	// also probes addresses shared with PCF8574/PCF8591/MCP4725-style chips; may change their outputs
	if err != nil {
		panic(err)
	}
	fmt.Println(len(devices))
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
