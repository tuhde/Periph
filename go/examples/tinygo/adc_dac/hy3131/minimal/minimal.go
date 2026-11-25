//go:build tinygo

// HY3131 minimal example — TinyGo target.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.SPI0.Configure(machine.SPIConfig{
		Frequency: 1_000_000,
		Mode:      0,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
	})
	cs := machine.GP17
	conn := connection.NewSPIConnection(machine.SPI0, cs, nil, nil)

	chip, err := adcdac.NewHY3131Minimal(conn, 1.2) // Create HY3131 driver, (connection, vref=1.2 V) → (*HY3131Minimal, error)
	if err != nil {
		fmt.Println("init:", err)
		return
	}
	for {
		v, err := chip.ReadVoltage() // Read AD1 differential voltage, () → (float64 V, error)
		if err != nil {
			fmt.Println("read:", err)
			return
		}
		fmt.Printf("%.5f\n", v)
	}
}
