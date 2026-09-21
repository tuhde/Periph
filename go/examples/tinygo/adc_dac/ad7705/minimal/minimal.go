//go:build tinygo

// AD7705 minimal example — TinyGo target.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.SPI0.Configure(machine.SPIConfig{
		Frequency: 5_000_000,
		Mode:      3,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
	})
	cs := machine.GP17
	conn := connection.NewSPIConnection(machine.SPI0, cs, nil, nil)                  // Create SPI connection, (spi=SPI0, cs=GP17) → (*SPIConnection)

	chip, err := adcdac.NewAD7705Minimal(conn, 2.5, adcdac.MCLK2_4576MHz, nil)           // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nil) → (*AD7705Minimal, error)
	if err != nil {
		fmt.Println("init:", err)
		return
	}

	v, err := chip.ReadVoltage()                                                    // Read Channel 1 voltage, () → (float64, error)
	if err != nil {
		fmt.Println("read:", err)
		return
	}
	fmt.Printf("%.4f\n", v)
}
