//go:build tinygo

// HX711 complete example — TinyGo / Raspberry Pi Pico W.
//
// Wires HX711 DOUT to GP2 and PD_SCK to GP3. Exercises every method
// in the HX711Full API: raw and averaged reads, gain selection,
// tare, scale factor, weight reading, and power management.
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
	hx, err := adcdac.NewHX711Full(conn)                           // Create HX711 driver, (connection) → (*HX711Full, error)
	if err != nil {
		panic(err)
	}

	ready, err := hx.IsReady() // Check if data is ready, () → (bool, error)
	if err != nil {
		fmt.Printf("is_ready: %v\n", err)
		return
	}
	fmt.Printf("is_ready: %v\n", ready)

	raw, err := hx.ReadRaw() // Read 24-bit ADC value, () → (int32, error)
	if err != nil {
		fmt.Printf("read_raw: %v\n", err)
		return
	}
	fmt.Printf("read_raw: %d\n", raw)

	avg, err := hx.ReadAverage(5) // Read averaged value, (times=5) → (int32, error)
	if err != nil {
		fmt.Printf("read_average: %v\n", err)
		return
	}
	fmt.Printf("read_average(5): %d\n", avg)

	if err := hx.SetGain(adcdac.HX711Gain64); err != nil { // Select gain 64, (gain=64) → error
		fmt.Printf("set_gain 64: %v\n", err)
	}
	if err := hx.SetGain(adcdac.HX711Gain32); err != nil { // Select gain 32, (gain=32) → error
		fmt.Printf("set_gain 32: %v\n", err)
	}
	if err := hx.SetGain(adcdac.HX711Gain128); err != nil { // Select gain 128, (gain=128) → error
		fmt.Printf("set_gain 128: %v\n", err)
	}

	if err := hx.Tare(5); err != nil { // Capture zero offset, (times=5) → error
		fmt.Printf("tare: %v\n", err)
	}
	offset := hx.GetOffset() // Get tare offset, () → int32
	fmt.Printf("get_offset: %d\n", offset)

	hx.SetScale(420.0)   // Set scale factor, (factor=420.0) → ()
	scale := hx.GetScale() // Get scale factor, () → float32
	fmt.Printf("get_scale: %.1f\n", scale)

	weight, err := hx.ReadWeight(3) // Read calibrated weight, (times=3) → (float32, error)
	if err != nil {
		fmt.Printf("read_weight: %v\n", err)
		return
	}
	fmt.Printf("read_weight: %.1f g\n", weight)

	if err := hx.PowerDown(); err != nil { // Enter power-down, () → error
		fmt.Printf("power_down: %v\n", err)
	}
	time.Sleep(100 * time.Millisecond)
	if err := hx.PowerUp(); err != nil { // Wake chip, () → error
		fmt.Printf("power_up: %v\n", err)
	}
}
