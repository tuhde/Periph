//go:build tinygo

// IS31FL3731 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	must(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x74, nil, nil) // Create I2C connection, (i2c, addr=0x74) → (*I2CConnection)

	matrix, err := led.NewIs31fl3731Minimal(conn) // Create IS31FL3731 driver, (connection) → (*Is31fl3731Minimal, error)
	if err != nil {
		panic(err)
	}

	for col := uint8(0); col < led.Is31fl3731Cols; col++ {
		must(matrix.SetPixel(0, col, 128)) // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → error
	}
	time.Sleep(time.Second)
	must(matrix.Fill(32)) // Set every LED's PWM brightness, (brightness 0–255) → error
	time.Sleep(time.Second)
	must(matrix.Clear()) // Switch all LEDs off, () → error
	fmt.Println("===DONE: 0 passed, 0 failed===")
}
