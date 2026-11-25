//go:build tinygo

// PCA9685 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint
var _ = math.Pi

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

	conn := connection.NewI2CConnection(i2c, 0x40, nil, nil) // Create I2C connection, (i2c, addr=0x40) → (*I2CConnection)

	pwm, err := led.NewPca9685Minimal(conn) // Create PCA9685 driver at 1 kHz, (connection) → (*Pca9685Minimal, error)
	must(err)

	for step := 0; step <= 10; step++ {
		must(pwm.SetDuty(0, float64(step)/10)) // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → error
		time.Sleep(200 * time.Millisecond)
	}
	must(pwm.SetDuty(0, 0))
	fmt.Println("===DONE: 0 passed, 0 failed===")
}
