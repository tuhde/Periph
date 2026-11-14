//go:build tinygo

// AM2315 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Constructs the driver, then reads temperature and humidity every two seconds.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
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
		Frequency: 100_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil) // Create I2C connection, (i2c, addr=0x5C, ) → (*I2CConnection)

	chip := environmental.NewAM2315Minimal(conn) // Create AM2315 driver, (connection) → *AM2315Minimal
	for {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
		// previous measurement; reads are kept >= 2 s apart
		chk(err)
		fmt.Printf("%.1f C, %.1f %%RH\n", t, rh)
		time.Sleep(2 * time.Second)
	}
}
