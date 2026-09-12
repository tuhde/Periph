//go:build tinygo

// HX710A demo example — TinyGo / Raspberry Pi Pico W.
//
// Temperature-monitored load cell on a Pico W. Tares the scale at
// startup (averaging 10 readings), then loops reading the weight
// every 500 ms and printing the result in grams. Only emits a
// "-> N g" line when the value changes by more than 1 g. Every 10th
// iteration (~5 s), also samples the on-chip temperature sensor —
// an uncalibrated ADC code for relative drift tracking, not an
// absolute °C reading. scaleFactor is calibrated for a 100 g
// reference weight; substitute your own.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

const scaleFactor = 420.0

func main() {
	conn := connection.NewHX711Connection(machine.GP2, machine.GP3, nil) // Create HX711 transport connection, (dout=GP2, pd_sck=GP3) → (*HX711Connection)
	hx, err := adcdac.NewHX710AFull(conn)                                // Create HX710A driver, (connection) → (*HX710AFull, error)
	if err != nil {
		panic(err)
	}

	// --- Tare the scale at startup ---
	fmt.Println("Taring...")
	if err := hx.Tare(10); err != nil { // Capture zero offset, (times=10) → error
		fmt.Printf("tare: %v\n", err)
		return
	}
	hx.SetScale(scaleFactor) // Set scale factor, (factor=scaleFactor) → ()
	fmt.Println("Ready. Place an item on the scale.")

	// --- Continuous read loop with change-only printing and periodic temperature ---
	var lastWeight float32
	first := true
	iteration := 0
	for {
		w, err := hx.ReadWeight(3) // Read calibrated weight, (times=3) → (float32, error)
		if err != nil {
			fmt.Printf("read_weight: %v\n", err)
			time.Sleep(500 * time.Millisecond)
			continue
		}
		if first || (w-lastWeight > 1.0) || (lastWeight-w > 1.0) {
			fmt.Printf("-> %.1f g\n", w)
			lastWeight = w
			first = false
		}

		if iteration%10 == 0 {
			tempRaw, err := hx.ReadTemperatureRaw() // Read raw on-chip temperature code, () → (int32, error)
			if err == nil {
				fmt.Printf("temp raw=%d\n", tempRaw)
			}
		}
		iteration++

		time.Sleep(500 * time.Millisecond)
	}
}
