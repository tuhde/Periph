//go:build tinygo

// L3G4200D minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL,
// constructs the driver, and prints X/Y/Z angular rate in a loop.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gyroscope"
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

	conn := connection.NewI2CConnection(i2c, 0x68, nil, nil)        // Create I2C connection, (i2c, addr=0x68) → (*I2CConnection)
	chip, err := gyroscope.NewL3G4200DMinimal(conn, false)         // Create L3G4200D driver, (connection) → (*L3G4200DMinimal, error)
	if err != nil {
		panic(err)
	}

	for i := 0; i < 10; i++ {
		x, y, z, err := chip.AngularRate() // Read X/Y/Z angular rate, () → (float32, float32, float32) rad/s
		if err != nil {
			println("read:", err.Error())
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("X=%.3f Y=%.3f Z=%.3f rad/s\n", x, y, z)
		time.Sleep(100 * time.Millisecond)
	}
}
