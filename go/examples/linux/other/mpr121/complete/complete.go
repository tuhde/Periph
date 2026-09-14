//go:build linux && !tinygo

// MPR121 complete example — Linux host.
//
// Exercises every Full-class method.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/chips/other"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5A"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5A) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := other.NewMPR121Full(conn) // Create MPR121 driver, (connection) → (*MPR121Full, error)
	if err != nil {                        // runs Minimal init then exposes Full configuration methods
		panic(err)
	}

	if err := chip.Stop(); err != nil { // Enter Stop Mode, () → error
		panic(err) // required before writing most config registers
	}
	if err := chip.ConfigureThresholds(0, 15, 8); err != nil { // Set thresholds, (electrode=0, touch=15, release=8) → error
		panic(err) // ELE0: touch at 15 LSBs below baseline, release at 8 LSBs
	}
	if err := chip.ConfigureAllThresholds(12, 6); err != nil { // Apply thresholds to all, (touch=12, release=6) → error
		panic(err) // ELE1..ELE11: same touch/release values
	}
	if err := chip.ConfigureProximityThresholds(8, 4); err != nil { // Set ELEPROX thresholds, (touch=8, release=4) → error
		panic(err) // ELEPROX touch at 8 LSBs, release at 4
	}
	if err := chip.ConfigureBaselineFilter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0); err != nil { // Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → error
		panic(err) // baseline filter defaults; tracks capacitance drift only
	}
	if err := chip.ConfigureSampling(16, 1, 0, 0, 4); err != nil { // Set AFE config, (cdc=16, cdt=1, ffi=0, sfi=0, esi=4) → error
		panic(err) // 16 µA global CDC, 0.5 µs charge time, 16 ms sample interval
	}
	if err := chip.ConfigureDebounce(1, 1); err != nil { // Set debounce, (touch=1, release=1) → error
		panic(err) // one consecutive measurement to detect touch/release
	}
	if err := chip.ConfigureAutoconfig(3300, 0, false, true, true); err != nil { // Configure autoconfig, (vdd_mv=3300, retry=0, scts=false, are=true, ace=true) → error
		panic(err) // recompute USL/TL/LSL for 3.3 V supply
	}
	if err := chip.Start(12, 2, 0); err != nil { // Enter Run Mode, (n_electrodes=12, cl=2, eleprox_en=0) → error
		panic(err) // all 12 electrodes, baseline init from first measurement, proximity off
	}

	for i := 0; i < 10; i++ {
		time.Sleep(200 * time.Millisecond)
		t, err := chip.Touched() // Read 12-bit touch bitmask, () → (uint16 bitmask, error)
		if err != nil {
			panic(err)
		}
		f0, err := chip.Filtered(0) // Read ELE0 filtered, (electrode=0) → (uint16 0..1023, error)
		if err != nil {
			panic(err)
		}
		b0, err := chip.Baseline(0) // Read ELE0 baseline, (electrode=0) → (uint16 0..1023, error)
		if err != nil {
			panic(err)
		}
		oor, err := chip.OORStatus() // Read OOR bitmask, () → (uint16 bitmask, error)
		if err != nil {
			panic(err)
		}
		pt, err := chip.ProximityTouched() // Read proximity touched, () → (bool, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("t=0x%03X f0=%d b0=%d oor=0x%04X pt=%v\n", t, f0, b0, oor, pt)
	}
	if err := chip.EnableInterrupt(other.SOURCE_OOR); err != nil { // Enable interrupt source, (source=SOURCE_OOR) → error
		panic(err) // OOR will now assert INT as well as touch/release
	}
	if err := chip.DisableInterrupt(other.SOURCE_OOR); err != nil { // Disable interrupt source, (source=SOURCE_OOR) → error
		panic(err) // OOR no longer asserts INT
	}
	if err := chip.ClearOvercurrent(); err != nil { // Clear OVCF, () → error
		panic(err) // safe no-op when overcurrent has not occurred
	}
	if err := chip.Reset(); err != nil { // Soft reset, () → error
		panic(err) // reapplies Minimal defaults; device ready for fresh use
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
