//go:build tinygo

// HX711 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Wires HX711 DOUT to GP2 and PD_SCK to GP3. Reads signed 24-bit
// ADC values from Channel A at Gain 128 every 500 ms.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	conn := connection.NewHX711Connection(machine.GP2, machine.GP3, nil) // Create HX711 connection, (dout=GP2, pd_sck=GP3) → (*HX711Connection)
	hx, err := adcdac.NewHX711Minimal(conn)                          // Create HX711 driver, (connection) → (*HX711Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		raw, err := hx.ReadRaw() // Read 24-bit ADC value, () → (int32, error)
		if err != nil {
			fmt.Printf("read_raw: %v\n", err)
			time.Sleep(500 * time.Millisecond)
			continue
		}
		fmt.Printf("raw=%d\n", raw)
		time.Sleep(500 * time.Millisecond)
	}
}
