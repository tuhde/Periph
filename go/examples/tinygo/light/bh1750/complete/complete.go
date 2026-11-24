//go:build tinygo

// BH1750 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every BH1750Full method once.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x23, nil, nil) // Create I2C connection, (i2c, addr=0x23, ) → (*I2CConnection)

	chip := light.NewBH1750Full(conn) // Create BH1750 driver, (connection) → *BH1750Full
	chk(chip.Init())                  // Power on, () → error
	// opcode 0x01; the chip powers down by itself after each one-time measurement

	lux, err := chip.Read() // Measure illuminance, () → (float32 lx, error)
	// one-time H-resolution (1 lx), waits 180 ms
	chk(err)
	fmt.Printf("illuminance %.1f lx\n", lux)
	raw, err := chip.ReadRaw() // Read the raw count, () → (uint16, error)
	// 16-bit, one one-time measurement in the current mode
	chk(err)
	fmt.Println("raw", raw)
	h2, err := chip.MeasureOnce(light.BH1750ModeH2) // One-time measurement, (mode ModeH|ModeH2|ModeL) → (float32 lx, error)
	// ModeH2 = 0.5 lx steps, ModeL = 4 lx steps and 24 ms
	chk(err)
	fmt.Printf("H2 %.2f lx\n", h2)
	fmt.Printf("measurement time %.3f s\n", chip.MeasurementTime(light.BH1750ModeH)) // Maximum measurement time, (mode) → float32 s
	fmt.Printf("lux per count %.4f\n", chip.LuxPerCount(light.BH1750ModeH))          // Lux per raw count, (mode) → float32

	chk(chip.StartContinuous(light.BH1750ModeH)) // Start continuous mode, (mode) → error
	// first result after MeasurementTime()
	time.Sleep(200 * time.Millisecond)
	c, err := chip.ReadContinuous() // Read the latest result, () → (float32 lx, error)
	// no wait
	chk(err)
	fmt.Printf("continuous %.1f lx\n", c)

	chk(chip.SetMeasurementTime(138)) // Set MTreg, (mtreg 31-254) → error
	// default 69; larger = more sensitive and slower
	fmt.Println("mtreg", chip.GetMeasurementTimeRegister()) // Get MTreg, () → uint8
	// cached, the chip has no read-back
	chk(chip.SetSensitivity(1.0)) // Set sensitivity, (factor 0.45-3.68) → error
	// MTreg = round(69 x factor)

	chk(chip.PowerDown()) // Power down, () → error
	chk(chip.PowerOn())   // Power on, () → error
	chk(chip.Reset())     // Clear the data register, () → error
	// powers on first; MTreg and mode are kept
}
