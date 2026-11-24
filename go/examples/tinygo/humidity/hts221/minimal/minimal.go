//go:build tinygo

// HTS221 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then reads temperature and humidity every two seconds.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
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

	conn := connection.NewI2CConnection(i2c, 0x5F, nil, nil) // Create I2C connection, (i2c, addr=0x5F, ) → (*I2CConnection)

	chip := humidity.NewHTS221Minimal(conn) // Create HTS221 driver, (connection) → *HTS221Minimal
	chk(chip.Init())                        // Read calibration and start one-shot mode, () → error

	for {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %rH, error)
		chk(err)
		fmt.Printf("%.2f C, %.1f %%rH\n", t, rh)
		time.Sleep(2 * time.Second)
	}
}
