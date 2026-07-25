//go:build linux && !tinygo

// MCP23017 demo example — Linux host.
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
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x20"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x20) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := ioexpander.NewMCP23017Full(tr, uint8(addr)) // Create MCP23017 full driver, (transport, addr=0x20) → (*MCP23017Full, error)
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

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
