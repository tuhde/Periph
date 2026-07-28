//go:build tinygo

// DHT11 complete example — TinyGo / Raspberry Pi Pico W.
//
// Wires the DHT11 DATA line to GP15. Exercises every method in the
// DHT11Full API: combined read, separate temperature/humidity
// accessors, raw frame access, and retry-on-checksum-error reads.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	conn := connection.NewDHTxxConnection(machine.GP15, nil) // Create DHTxx connection, (pin=GP15) → (*DHTxxConnection)
	dht, err := humidity.NewDHT11Full(conn)           // Create DHT11 driver, (connection) → (*DHT11Full, error)
	if err != nil {
		panic(err)
	}

	t, h, err := dht.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	if err != nil {
		fmt.Printf("read: %v\n", err)
		return
	}
	fmt.Printf("read: T=%.1f C  H=%.1f %%RH\n", t, h)

	tt, err := dht.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	if err != nil {
		fmt.Printf("read_temperature: %v\n", err)
		return
	}
	fmt.Printf("read_temperature: %.1f C\n", tt)

	hh, err := dht.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	if err != nil {
		fmt.Printf("read_humidity: %v\n", err)
		return
	}
	fmt.Printf("read_humidity: %.1f %%RH\n", hh)

	frame, err := dht.ReadRaw() // Read raw 5-byte frame, () → ([]byte, error)
	if err != nil {
		fmt.Printf("read_raw: %v\n", err)
		return
	}
	fmt.Printf("read_raw: [0x%02X, 0x%02X, 0x%02X, 0x%02X, 0x%02X]\n",
		frame[0], frame[1], frame[2], frame[3], frame[4])

	rt, rh, err := dht.ReadRetry(5) // Read with retry, (max_retries=5) → (float32 °C, float32 %RH, error)
	if err != nil {
		fmt.Printf("read_retry: %v\n", err)
		return
	}
	fmt.Printf("read_retry: T=%.1f C  H=%.1f %%RH\n", rt, rh)
}
