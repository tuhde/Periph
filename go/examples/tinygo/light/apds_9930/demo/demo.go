//go:build tinygo

// APDS-9930 demo example — TinyGo / Raspberry Pi Pico W.
// Adaptive backlight + screen-lock scenario.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	dimLuxThreshold = 10.0
	proxScreenOff  = 400
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
	if err != nil {                           // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive
		panic(err)
	}

	for i := 0; i < 30; i++ {
		time.Sleep(time.Second)
		lx, _ := chip.Lux()
		p, _ := chip.Proximity()
		fmt.Printf("lux=%.1f lx  proximity=%d\n", lx, p)
		if lx < dimLuxThreshold {
			fmt.Println("  -> dim backlight")
		}
		if p > proxScreenOff {
			fmt.Println("  -> disable screen")
		}
	}
}