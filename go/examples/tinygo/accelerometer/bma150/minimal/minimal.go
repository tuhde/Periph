//go:build tinygo

// BMA150 minimal example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	conn, err := connection.NewI2CConnection(machine.I2C0, 0x38, nil, nil)
	if err != nil {
		fmt.Println("connection:", err)
		return
	}
	chip, err := accelerometer.NewBMA150Minimal(conn)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	for {
		x, y, z, err := chip.Read()
		if err != nil {
			fmt.Println("read:", err)
			return
		}
		fmt.Printf("x=%.3f y=%.3f z=%.3f g\n", x, y, z)
		time.Sleep(100 * time.Millisecond)
	}
}
