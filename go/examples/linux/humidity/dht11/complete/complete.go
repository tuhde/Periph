//go:build linux && !tinygo

// DHT11 complete example — Linux host.
//
// Exercises every method in the DHT11Full API: combined read,
// separate temperature/humidity accessors, raw frame access, and
// retry-on-checksum-error reads.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	line, err := strconv.Atoi(envOr("DHT11_LINE", "4"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewDHTxxConnection(line, -1, nil) // Create DHTxx connection, (line=4, out_line=-1) → (*DHTxxConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	dht, err := humidity.NewDHT11Full(conn) // Create DHT11 driver, (connection) → (*DHT11Full, error)
	if err != nil {
		panic(err)
	}

	t, h, err := dht.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	// sends start pulse, reads 5-byte frame, validates checksum, decodes
	if err != nil {
		fmt.Fprintln(os.Stderr, "read:", err)
		return
	}
	fmt.Printf("read: T=%.1f C  H=%.1f %%RH\n", t, h)

	tt, err := dht.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	// internally calls Read() and returns the temperature field
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_temperature:", err)
		return
	}
	fmt.Printf("read_temperature: %.1f C\n", tt)

	hh, err := dht.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	// internally calls Read() and returns the humidity field
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_humidity:", err)
		return
	}
	fmt.Printf("read_humidity: %.1f %%RH\n", hh)

	frame, err := dht.ReadRaw() // Read raw 5-byte frame, () → ([]byte, error)
	// returns [hum_int, hum_dec, temp_int, temp_dec, checksum] after checksum validation
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_raw:", err)
		return
	}
	fmt.Printf("read_raw: [0x%02X, 0x%02X, 0x%02X, 0x%02X, 0x%02X]\n",
		frame[0], frame[1], frame[2], frame[3], frame[4])

	rt, rh, err := dht.ReadRetry(5) // Read with retry, (max_retries=5) → (float32 °C, float32 %RH, error)
	// retries up to max_retries times on checksum error before giving up
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_retry:", err)
		return
	}
	fmt.Printf("read_retry: T=%.1f C  H=%.1f %%RH\n", rt, rh)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
