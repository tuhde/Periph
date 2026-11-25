//go:build linux && !tinygo

// HTS221 complete example — Linux host.
//
// Calls every HTS221Full method once.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5F"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5F) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := humidity.NewHTS221Full(conn) // Create HTS221 driver, (connection) → *HTS221Full
	chk(chip.Init())                     // Read calibration and start one-shot mode, () → error
	// decodes the per-device two-point calibration

	id, err := chip.WhoAmI() // Read WHO_AM_I, () → (uint8, error)
	// 0xBC identifies the chip
	chk(err)
	fmt.Printf("who am i 0x%02X\n", id)
	t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %rH, error)
	// one-shot conversion, converted with the calibration
	chk(err)
	fmt.Printf("%.2f C, %.1f %%rH\n", t, rh)
	h, err := chip.ReadHumidity() // Read humidity only, () → (float32 %rH, error)
	// one-shot conversion, clamped to 0–100
	chk(err)
	fmt.Printf("humidity %.1f %%rH\n", h)
	tc, err := chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	// one-shot conversion
	chk(err)
	fmt.Printf("temperature %.2f C\n", tc)
	rawH, err := chip.ReadRawHumidity() // Read raw humidity, () → (int16, error)
	// signed 16-bit ADC output
	chk(err)
	rawT, err := chip.ReadRawTemperature() // Read raw temperature, () → (int16, error)
	// signed 16-bit ADC output
	chk(err)
	fmt.Println("raw humidity", rawH, "raw temperature", rawT)
	fmt.Printf("calibration %+v\n", chip.ReadCalibration()) // Read calibration, () → HTS221Calibration
	// decoded two-point coefficients
	dew, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	// Magnus formula over one measurement
	chk(err)
	fmt.Printf("dew point %.2f C\n", dew)
	st, err := chip.Status() // Read STATUS_REG, () → (uint8, error)
	// H_DA = 0x02, T_DA = 0x01
	chk(err)
	ready, err := chip.DataReady() // Check data available, () → (bool, error)
	// both H_DA and T_DA set
	chk(err)
	fmt.Printf("status 0x%02X data ready %v\n", st, ready)

	chk(chip.SetOutputDataRate(1)) // Set output data rate, (hz 0|1|7|12.5) → error
	// continuous sampling at 1 Hz
	hz, err := chip.GetOutputDataRate() // Get output data rate, () → (float32 Hz, error)
	// decodes CTRL_REG1.ODR
	chk(err)
	fmt.Println("rate", hz, "Hz")
	time.Sleep(1200 * time.Millisecond)
	ct, crh, err := chip.ReadContinuous() // Read latest sample, () → (float32 °C, float32 %rH, error)
	// no conversion is started
	chk(err)
	fmt.Printf("continuous %.2f C, %.1f %%rH\n", ct, crh)
	chk(chip.SetOutputDataRate(0)) // Set output data rate, (hz 0|1|7|12.5) → error
	// back to one-shot mode

	chk(chip.SetAveraging(32, 64)) // Set averaging, (temperatureSamples 2–256, humiditySamples 4–512) → error
	// more samples lower the noise and lengthen the conversion
	ta, ha, err := chip.GetAveraging() // Get averaging, () → (int, int, error)
	// (temperature samples, humidity samples)
	chk(err)
	fmt.Println("averaging", ta, ha)
	chk(chip.SetAveraging(16, 32))
	chk(chip.SetBlockDataUpdate(true)) // Set block data update, (enabled) → error
	// keeps LSB/MSB of one sample consistent

	chk(chip.SetHeater(true)) // Switch the heater, (on) → error
	// drives off condensation; do not measure while on
	on, err := chip.IsHeaterOn() // Heater state, () → (bool, error)
	// reads CTRL_REG2.HEATER
	chk(err)
	fmt.Println("heater on:", on)
	chk(chip.SetHeater(false))

	chk(chip.ConfigureDRDY(true, true, false)) // Configure DRDY pin, (enabled, activeHigh, openDrain) → error
	// routes data-ready to the DRDY pin
	ps, err := chip.PollInterrupt() // Poll interrupt status, () → (uint8, error)
	// STATUS_REG; flags clear when the data is read
	chk(err)
	fmt.Printf("poll 0x%02X\n", ps)
	chk(chip.ConfigureDRDY(false, true, false))

	chk(chip.PowerDown()) // Power down, () → error
	// clears CTRL_REG1.PD
	chk(chip.PowerUp()) // Power up, () → error
	// sets CTRL_REG1.PD
	chk(chip.OnInterrupt(func(s uint8) { fmt.Printf("DRDY status 0x%02X\n", s) })) // Subscribe to data-ready, (callback func(uint8)) → error
	// edge on the pin or a polling goroutine
	time.Sleep(2 * time.Second)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	// disables the DRDY output
	chk(chip.Reboot()) // Reload trimming, () → error
	// sets BOOT, waits, re-reads the calibration
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
