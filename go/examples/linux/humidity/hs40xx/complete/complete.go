//go:build linux && !tinygo

// HS40xx complete example — Linux host.
//
// Calls every hs40xx api method once.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x54"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x54) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := humidity.NewHS40xxFull(conn) // Create HS40xx driver, (connection) → *HS40xxFull

	rhHigh, rhReset := float32(60), float32(55)
	t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	// no-hold 0xF5, 5 bytes, CRC verified; one retry on mismatch
	chk(err)
	fmt.Println(t, rh)
	t, err = chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	// no-hold 0xF3, 3 bytes
	chk(err)
	fmt.Println(t)
	rh, err = chip.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	chk(err)
	fmt.Println(rh)
	t, rh, err = chip.ReadHold() // Read with clock stretching, () → (float32 °C, float32 %RH, error)
	// 0xE5; needs a stretching controller and SCL >= 200 kHz
	chk(err)
	rhRaw, tRaw, err := chip.ReadRaw() // Read raw values, () → (uint16, uint16, error)
	// (humidity, temperature); 14-bit, CRC verified
	chk(err)
	fmt.Println(rhRaw, tRaw)
	chk(chip.SetResolution(12, 12)) // Set resolution, (humidityBits, temperatureBits int) → error
	// 8, 10, 12 or 14 bit; 0 leaves a channel unchanged
	hb, tb, err := chip.GetResolution() // Get resolution, () → (humidityBits, temperatureBits int, error)
	chk(err)
	fmt.Println(hb, tb)
	reg, err := chip.ReadRegister(0x00) // Read a register, (reg byte) → (byte, error)
	chk(err)
	fmt.Println(reg)
	chk(chip.WriteRegister(0x00, 0x0F)) // Write a register, (reg, value byte) → error
	// 0x0F = 14 bit for both channels (default)
	chk(chip.SetAlertThresholds(humidity.HS40xxThresholds{RHHigh: &rhHigh, RHHighReset: &rhReset})) // Set alert thresholds, (HS40xxThresholds) → error
	// %RH / °C; nil leaves a threshold unchanged
	chk(chip.SetAlertPolarity(true))                                                       // Set ALERT polarity, (activeHigh bool) → error
	chk(chip.EnableInterrupt(humidity.HS40xxSourceRHHigh | humidity.HS40xxSourceTempHigh)) // Enable alert sources, (source byte) → error
	chk(chip.StartPeriodic(1))                                                             // Start periodic mode, (periodS float64) → error
	// 0.5, 1 or 2.5 s
	time.Sleep(1500 * time.Millisecond)
	p, err := chip.ReadPeriodic() // Fetch the latest periodic result, () → (HS40xxPeriodic, error)
	chk(err)
	fmt.Println(p)
	status, err := chip.PollInterrupt() // Read alert status, () → (byte, error)
	// HS40xxSource* bitmask
	chk(err)
	fmt.Println(status)
	chk(chip.OnInterrupt(func(s byte) { fmt.Println("alert", s) })) // Subscribe to the ALERT pin, (callback func(status byte)) → error
	// polls at the periodic interval without a pin
	time.Sleep(5 * time.Second)
	chk(chip.OffInterrupt())                                                                // Unsubscribe, () → error
	chk(chip.DisableInterrupt(humidity.HS40xxSourceRHHigh | humidity.HS40xxSourceTempHigh)) // Disable alert sources, (source byte) → error
	chk(chip.StopPeriodic())                                                                // Stop periodic mode, () → error
	id, err := chip.ReadID()                                                                // Read the 32-bit sensor ID, () → (uint32, error)
	chk(err)
	fmt.Printf("%#x\n", id)
	dp, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	// one measurement, Magnus formula
	chk(err)
	fmt.Println(dp)
	chk(chip.SoftReset()) // Soft reset, () → error
	// stops periodic mode, waits 500 ms
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
