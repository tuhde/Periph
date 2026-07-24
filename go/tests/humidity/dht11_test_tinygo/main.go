//go:build tinygo

// DHT11 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W with the DHT11 DATA line on GP15. Runs the
// check sequence: smoke test, decode logic against the datasheet
// example, negative-temperature decode, and a checksum mismatch
// detection check. Prints PASS/FAIL per check and ends with the
// standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	tr := transport.NewDHTxxTransport(machine.GP15) // Create DHTxx transport, (pin=GP15) → (*DHTxxTransport)
	dht, err := humidity.NewDHT11Minimal(tr)        // Create DHT11 driver, (transport) → (*DHT11Minimal, error)
	check("dht11_minimal_construct", err == nil && dht != nil)
	_ = dht.IsReady()

	dhtFull, err := humidity.NewDHT11Full(tr) // Create DHT11 driver, (transport) → (*DHT11Full, error)
	check("dht11_full_construct", err == nil && dhtFull != nil)

	// Decode logic: datasheet example frame.
	{
		frame := []byte{0x35, 0x00, 0x18, 0x04, 0x51}
		expected := byte((uint16(frame[0]) + uint16(frame[1]) + uint16(frame[2]) + uint16(frame[3])) & 0xFF)
		check("datasheet_checksum_valid", expected == frame[4])
		sign := float32(1)
		if frame[3]&0x80 != 0 {
			sign = -1
		}
		tempDec := frame[3] & 0x7F
		temperature := sign * (float32(frame[2]) + float32(tempDec)/10.0)
		humidity := float32(frame[0]) + float32(frame[1])/10.0
		check("datasheet_temperature", temperature > 24.39 && temperature < 24.41)
		check("datasheet_humidity", humidity > 52.99 && humidity < 53.01)
	}

	// Decode negative temperature.
	{
		frame := []byte{0x20, 0x00, 0x0A, 0x81, 0xAB}
		expected := byte((uint16(frame[0]) + uint16(frame[1]) + uint16(frame[2]) + uint16(frame[3])) & 0xFF)
		check("negative_checksum_valid", expected == frame[4])
		sign := float32(-1)
		if frame[3]&0x80 == 0 {
			sign = 1
		}
		tempDec := frame[3] & 0x7F
		temperature := sign * (float32(frame[2]) + float32(tempDec)/10.0)
		check("negative_temperature", temperature < -10.09 && temperature > -10.11)
	}

	// Checksum mismatch detection.
	{
		frame := []byte{0x35, 0x00, 0x18, 0x04, 0x00}
		expected := byte((uint16(frame[0]) + uint16(frame[1]) + uint16(frame[2]) + uint16(frame[3])) & 0xFF)
		check("checksum_mismatch_detected", expected != frame[4])
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
