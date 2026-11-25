//go:build tinygo

// SHT31 minimal example — TinyGo / Raspberry Pi Pico W.
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

	conn := connection.NewI2CConnection(i2c, 0x44, nil, nil) // Create I2C connection, (i2c, addr=0x44, ) → (*I2CConnection)

	chip, err := humidity.NewSHT31Minimal(conn) // Create SHT31 driver, (connection) → (*SHT31Minimal, error)
	chk(err)

	for {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
		chk(err)
		fmt.Printf("%.2f C, %.1f %%RH\n", t, rh)
		time.Sleep(2 * time.Second)
	}
}
