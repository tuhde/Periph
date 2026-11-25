//go:build tinygo

// LSM303 minimal example — TinyGo / Raspberry Pi Pico W.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	accelConn, err := connection.NewI2CConnection(machine.I2C0, accelerometer.LSM303I2CAddressAccel, nil, nil)
	if err != nil {
		fmt.Println("accelerometer connection:", err)
		return
	}
	magConn, err := connection.NewI2CConnection(machine.I2C0, accelerometer.LSM303I2CAddressMag, nil, nil)
	if err != nil {
		fmt.Println("magnetometer connection:", err)
		return
	}
	chip, err := accelerometer.NewLSM303Minimal(accelConn, magConn)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	for {
		ax, ay, az, err := chip.ReadAcceleration()
		if err != nil {
			fmt.Println("read acceleration:", err)
			return
		}
		mx, my, mz, err := chip.ReadMagnetic()
		if err != nil {
			fmt.Println("read magnetic:", err)
			return
		}
		fmt.Printf("accel x=%.4f y=%.4f z=%.4f g\n", ax, ay, az)
		fmt.Printf("mag   x=%.2f y=%.2f z=%.2f uT\n", mx, my, mz)
		time.Sleep(time.Second)
	}
}
