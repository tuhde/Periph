//go:build linux && !tinygo

// HS30xx demo example — Linux host.
//
// Hygrometer with raw values, status and dew point.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x44"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x44) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := humidity.NewHS30xxFull(conn) // Create HS30xx driver, (connection) → *HS30xxFull

	// Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
	powerCycle := func() { time.Sleep(100 * time.Millisecond) }

	// --- Optional: show and change the stored resolution (set showResolution) ---
	// The NVM is only reachable within 10 ms after power-up, so this needs powerCycle().
	const showResolution = false
	if showResolution {
		hb, tb, err := chip.GetResolution(powerCycle) // Get NVM resolution, (powerCycle func()) → (int, int, error)
		chk(err)
		fmt.Println("resolution", hb, tb)
		chk(chip.SetResolution(12, 12, powerCycle))         // Set NVM resolution, (humidityBits, temperatureBits int, powerCycle func()) → error
		chk(chip.SetMeasurementWait(15 * time.Millisecond)) // 12-bit RH + T need about 10 ms, (wait time.Duration) → error
	}

	// --- Hygrometer: one reading every 2 s for 30 s ---
	// Raw values and status come from the 14-bit data-fetch frame; status 0 means fresh data.
	fmt.Println("t(s)  RH raw  T raw  status  T (C)   RH (%)  Dew (C)")
	for n := 0; n < 15; n++ {
		rhRaw, tRaw, status, err := chip.ReadRaw() // Read raw values, () → (uint16, uint16, byte, error)
		chk(err)
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
		chk(err)
		dew, err := chip.DewPoint() // Dew point, () → (float32 °C, error)
		chk(err)
		fmt.Printf("%d  %d  %d  %d  %.2f  %.1f  %.2f\n", n*2, rhRaw, tRaw, status, t, rh, dew)
		time.Sleep(2 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
