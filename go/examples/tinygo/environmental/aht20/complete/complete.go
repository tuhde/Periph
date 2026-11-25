//go:build tinygo

// AHT20 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the AHT20Full API: status inspection, single
// and combined reads, raw values, status, dew point, and soft reset.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
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

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil) // Create I2C connection, (i2c, addr=0x38) → (*I2CConnection)
	chip, err := environmental.NewAHT20Full(conn)            // Create AHT20 driver, (connection) → (*AHT20Full, error)
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

	rh, rt, err := chip.ReadRaw()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_raw: RH=%d T=%d\n", rh, rt) // Read raw 20-bit values, () → (uint32, uint32, error)
	// same measurement as Read, undecoded

	_, _, crcOK, err := chip.ReadWithCRC()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_with_crc: crc_ok=%v\n", crcOK) // Read with CRC check, () → (float32 °C, float32 %RH, bool, error)
	// reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)

	st, err := chip.ReadStatus()
	if err != nil {
		panic(err)
	}
	fmt.Printf("read_status: 0x%02X\n", st) // Read status byte, () → (byte, error)
	// bit 7 BUSY, bit 3 CAL

	dew, err := chip.DewPoint()
	if err != nil {
		panic(err)
	}
	fmt.Printf("dew_point: %.2f C\n", dew) // Dew point, () → (float32 °C, error)
	// one fresh measurement, Magnus formula

	if err := chip.SoftReset(); err != nil { // Send soft reset command, () → error
		panic(err)
	}
	// sends 0xBA, waits 20 ms, re-runs the calibration check
}
