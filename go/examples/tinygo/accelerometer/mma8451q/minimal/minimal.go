//go:build tinygo

// MMA8451Q minimal example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	conn := connection.NewI2CConnection(machine.I2C0, 0x1C, nil, nil)
	chip, err := accelerometer.NewMMA8451QMinimal(conn) // Create MMA8451Q driver, (connection) → (MMA8451QMinimal, error)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	for {
		x, y, z, err := chip.Read() // Read acceleration, () → (float32 g, float32 g, float32 g, error)
		if err != nil {
			fmt.Println("read:", err)
			return
		}
		fmt.Printf("x=%.3f y=%.3f z=%.3f g\n", x, y, z)
		time.Sleep(100 * time.Millisecond)
	}
}
