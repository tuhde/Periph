//go:build tinygo

// AHT21 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the AHT21Full API: status inspection, single
// and combined reads, CRC-verified read, and soft reset.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C0
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x38)      // Create I2C transport, (i2c, addr=0x38) → (*I2CTransport)
	chip, err := environmental.NewAHT21Full(tr)   // Create AHT21 driver, (transport) → (*AHT21Full, error)
	if err != nil {
		panic(err)
	}

	cal, err := chip.IsCalibrated()
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_calibrated: %v\n", cal) // Check calibration status, () → (bool, error)
	// reads CAL bit from status byte

	busy, err := chip.IsBusy()
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_busy: %v\n", busy) // Check busy status, () → (bool, error)
	// reads BUSY bit from status byte

	t, h, err := chip.Read()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read: T=%.2f C  H=%.2f %%RH\n", t, h) // Trigger measurement, () → (float32 °C, float32 %RH, error)
	// sends 0xAC trigger, waits 80 ms, decodes 6 bytes

	time.Sleep(time.Second)

	tt, err := chip.ReadTemperature()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_temperature: %.2f C\n", tt) // Read temperature only, () → (float32 °C, error)
	// triggers full measurement, returns temperature_c

	hh, err := chip.ReadHumidity()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_humidity: %.2f %%RH\n", hh) // Read humidity only, () → (float32 %RH, error)
	// triggers full measurement, returns humidity_pct

	tc, hc, crcOk, err := chip.ReadWithCrc()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_with_crc: T=%.2f C  H=%.2f %%RH  CRC=%v\n", tc, hc, crcOk) // Read with CRC verification, () → (float32 °C, float32 %RH, bool, error)
	// reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)

	if err := chip.SoftReset(); err != nil { // Send soft reset command, () → error
		panic(err)
	}
	// sends 0xBA, waits 20 ms for recovery
}
