//go:build tinygo

// MCP23017 demo example — TinyGo / Raspberry Pi Pico W.
//
// Knight Rider scanner with button override.
//
// Hardware:
//   GPA0–GPA6: seven LEDs (anode → VCC via 220Ω, cathode → pin; active-high)
//   GPB0–GPB6: seven push buttons (pin → GND when pressed; pull-ups enabled)
//
// Runs a Knight Rider scanning pattern on PORTA. Pressing a button
// overrides the scanner and lights the matching LED. The loop reads
// GPIOB every 100 ms, builds the output mask from the button state
// (inverted, since active-low), ORs it with the scanner position
// unless a button is pressed, then writes to OLATA.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x20)                          // Create I2C transport, (i2c, addr=0x20) → (*I2CTransport)
	chip, err := ioexpander.NewMCP23017Full(tr, 0x20)                  // Create MCP23017 full driver, (transport, addr=0x20) → (*MCP23017Full, error)
	if err != nil {
		panic(err)
	}

	// Enable pull-ups on PORTB inputs (GPB0–GPB6) so idle buttons read high.
	if err := chip.ConfigurePullup(1, 0b01111111); err != nil { // Enable pull-ups, (port=1, mask) → error
		panic(err)
	}

	fmt.Println("Running — press buttons GPB0–GPB6 to light corresponding LEDs")

	position := 0
	direction := 1
	for {
		portb, err := chip.ReadPort(1) // Read PORTB, (port=1) → (uint8, error)
		if err != nil {
			panic(err)
		}
		// GPB0–GPB6 buttons: pressed = 0 (active-low pull-down)
		buttons := portb & 0x7F
		pressed := (^buttons) & 0x7F // invert: pressed button = bit 1

		scanner := uint8(1) << position

		output := uint8(0)
		if pressed != 0 {
			output = pressed | (1 << 7) // keep GPA7 high (output-only)
		} else {
			output = scanner | (1 << 7)
		}

		if err := chip.WritePort(0, output); err != nil { // Write PORTA via OLATA, (port=0, mask) → error
			panic(err)
		}

		ledStr := ""
		for i := 0; i < 7; i++ {
			if (output>>i)&1 == 1 {
				ledStr += "*"
			} else {
				ledStr += " "
			}
		}
		fmt.Printf("PORTA=0x%02X  [%s]  buttons=0x%02X\n", output, ledStr, buttons)

		position = position + direction
		if position == 6 {
			direction = -1
		}
		if position == 0 {
			direction = 1
		}

		time.Sleep(100 * time.Millisecond)
	}
}
