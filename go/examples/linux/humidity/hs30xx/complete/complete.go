//go:build linux && !tinygo

// HS30xx complete example — Linux host.
//
// Calls every hs30xx api method once.
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

	t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	// request, 40 ms wait, 4-byte fetch; one retry on stale data
	chk(err)
	fmt.Println(t, rh)
	t, err = chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	chk(err)
	fmt.Println(t)
	rh, err = chip.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	// 2-byte fetch
	chk(err)
	fmt.Println(rh)
	rhRaw, tRaw, status, err := chip.ReadRaw() // Read raw values, () → (uint16, uint16, byte, error)
	// 14-bit values; status 0 = valid, 1 = stale
	chk(err)
	fmt.Println(rhRaw, tRaw, status)
	chk(chip.SetMeasurementWait(60 * time.Millisecond)) // Override the post-request wait, (wait time.Duration) → error
	// default 40 ms; lower resolutions need less
	dew, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	// one measurement, Magnus formula
	chk(err)
	fmt.Println(dew)

	// NVM access only works within 10 ms after power-up, so it needs the powerCycle helper above.
	hb, tb, err := chip.GetResolution(powerCycle) // Get NVM resolution, (powerCycle func()) → (int, int, error)
	// (humidityBits, temperatureBits)
	chk(err)
	fmt.Println(hb, tb)
	chk(chip.SetResolution(12, 12, powerCycle)) // Set NVM resolution, (humidityBits, temperatureBits int, powerCycle func()) → error
	// 8, 10, 12 or 14 bit; 0 leaves a channel unchanged
	id, err := chip.ReadID(powerCycle) // Read the 32-bit sensor ID, (powerCycle func()) → (uint32, error)
	chk(err)
	fmt.Printf("%#x\n", id)
	chk(chip.EnterProgrammingMode()) // Enter programming mode, () → error
	// only within 10 ms after power-up
	reg, err := chip.ReadNVM(0x06) // Read an NVM register, (reg byte) → (uint16, error)
	// 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
	chk(err)
	fmt.Printf("%#x\n", reg)
	chk(chip.ExitProgrammingMode()) // Leave programming mode, () → error
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
