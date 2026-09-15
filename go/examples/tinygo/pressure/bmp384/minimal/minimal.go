//go:build tinygo

package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{
		Frequency: 400 * machine.KHz,
		SDA:       machine.GPIO0,
		SCL:       machine.GPIO1,
	})
	addr := uint8(0x76)

	tr, err := connection.NewI2CConnection(machine.I2C0, addr, nil, nil)
	if err != nil {
		fmt.Printf("connection: %v\n", err)
		panic(err)
	}
	defer tr.Close()

	chip, err := pressure.NewBMP384Minimal(tr)
	if err != nil {
		fmt.Printf("new: %v\n", err)
		panic(err)
	}

	for i := 0; i < 5; i++ {
		t, err := chip.Temperature()
		if err != nil {
			fmt.Printf("temperature: %v\n", err)
			panic(err)
		}
		p, err := chip.Pressure()
		if err != nil {
			fmt.Printf("pressure: %v\n", err)
			panic(err)
		}
		fmt.Printf("%.1f C, %.1f hPa\n", t, p)
		time.Sleep(time.Second)
	}
}
