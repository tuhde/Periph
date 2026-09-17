//go:build tinygo

// MPR121 complete example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/other"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x5A, nil, nil) // Create I2C connection, (i2c, addr=0x5A) → (*I2CConnection)
	chip, err := other.NewMPR121Full(conn)                    // Create MPR121 driver, (connection) → (*MPR121Full, error)
	if err != nil {                                           // runs Minimal init then exposes Full configuration methods
		panic(err)
	}
	if err := chip.Stop(); err != nil { // Enter Stop Mode, () → error
		panic(err)
	}
	if err := chip.ConfigureThresholds(0, 15, 8); err != nil { // Set thresholds, (electrode=0, touch=15, release=8) → error
		panic(err)
	}
	if err := chip.ConfigureAllThresholds(12, 6); err != nil { // Apply thresholds to all, (touch=12, release=6) → error
		panic(err)
	}
	if err := chip.ConfigureProximityThresholds(8, 4); err != nil { // Set ELEPROX thresholds, (touch=8, release=4) → error
		panic(err)
	}
	if err := chip.ConfigureBaselineFilter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0); err != nil { // Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → error
		panic(err)
	}
	if err := chip.ConfigureSampling(16, 1, 0, 0, 4); err != nil { // Set AFE config, (cdc=16, cdt=1, ffi=0, sfi=0, esi=4) → error
		panic(err)
	}
	if err := chip.ConfigureDebounce(1, 1); err != nil { // Set debounce, (touch=1, release=1) → error
		panic(err)
	}
	if err := chip.ConfigureAutoconfig(3300, 0, false, true, true); err != nil { // Configure autoconfig, (vdd_mv=3300, retry=0, scts=false, are=true, ace=true) → error
		panic(err)
	}
	if err := chip.Start(12, 2, 0); err != nil { // Enter Run Mode, (n_electrodes=12, cl=2, eleprox_en=0) → error
		panic(err)
	}

	for i := 0; i < 10; i++ {
		time.Sleep(200 * time.Millisecond)
		t, err := chip.Touched()
		if err != nil {
			fmt.Printf("touched: %v\n", err)
			continue
		}
		f0, err := chip.Filtered(0)
		if err != nil {
			fmt.Printf("filtered: %v\n", err)
			continue
		}
		b0, err := chip.Baseline(0)
		if err != nil {
			fmt.Printf("baseline: %v\n", err)
			continue
		}
		oor, err := chip.OORStatus()
		if err != nil {
			fmt.Printf("oor: %v\n", err)
			continue
		}
		pt, err := chip.ProximityTouched()
		if err != nil {
			fmt.Printf("proximity: %v\n", err)
			continue
		}
		fmt.Printf("t=0x%03X f0=%d b0=%d oor=0x%04X pt=%v\n", t, f0, b0, oor, pt)
	}
	if err := chip.EnableInterrupt(other.SOURCE_OOR); err != nil { // Enable interrupt source, (source=SOURCE_OOR) → error
		panic(err)
	}
	if err := chip.DisableInterrupt(other.SOURCE_OOR); err != nil { // Disable interrupt source, (source=SOURCE_OOR) → error
		panic(err)
	}
	if err := chip.ClearOvercurrent(); err != nil { // Clear OVCF, () → error
		panic(err)
	}
	if err := chip.Reset(); err != nil { // Soft reset, () → error
		panic(err)
	}
}
