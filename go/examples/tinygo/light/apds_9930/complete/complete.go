//go:build tinygo

// APDS-9930 complete example — TinyGo / Raspberry Pi Pico W.
// Exercises every Full-class method.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
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

	conn := connection.NewI2CConnection(i2c, 0x39, nil, nil)        // Create I2C connection, (i2c, addr=0x39) → (*I2CConnection)
	chip, err := light.NewAPDS9930Full(conn) // Create APDS-9930 Full driver, (connection) → (*APDS9930Full, error)
	if err != nil {                           // exposes ALS and proximity configuration methods
		panic(err)
	}

	if err := chip.ConfigureALS(0xDB, 0, false); err != nil { // Configure ALS, (atime=0xDB, again=0, agl=false) → error
		panic(err) // sets ALS integration time to 101 ms with 1x gain
	}
	if err := chip.ConfigureProximity(8, 0, 0, false, 0xFF); err != nil { // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → error
		panic(err) // 8 LED pulses at 100 mA, 1x gain, no reduced drive
	}
	if err := chip.DisableWait(); err != nil { // Disable wait timer, () → error
		panic(err)
	}
	if err := chip.SetAlsThresholds(100, 60000, 1); err != nil { // Set ALS thresholds, (low=100, high=60000, persistence=1) → error
		panic(err)
	}
	if err := chip.SetProximityThresholds(10, 200, 1); err != nil { // Set proximity thresholds, (low=10, high=200, persistence=1) → error
		panic(err)
	}
	if err := chip.SetProximityOffset(0); err != nil { // Set proximity offset, (offset=0) → error
		panic(err)
	}
	if err := chip.SleepAfterInterrupt(false); err != nil { // Configure SAI, (enable=false) → error
		panic(err)
	}

	for i := 0; i < 10; i++ {
		time.Sleep(110 * time.Millisecond)
		lx, _ := chip.Lux() // Read ambient illuminance, () → (float64 lx, error)
		p, _ := chip.Proximity()
		c0, _ := chip.Ch0() // Read Ch0 raw, () → (uint16 count, error)
		c1, _ := chip.Ch1() // Read Ch1 raw, () → (uint16 count, error)
		st, _ := chip.Status()
		fmt.Printf("lux=%.1f lx  prox=%d  ch0=%d  ch1=%d  status=%+v\n", lx, p, c0, c1, st)
	}
	if err := chip.ClearInterrupt(0); err != nil { // Clear interrupts, (channel=0) → error
		panic(err)
	}
}