//go:build linux && !tinygo

// MS5611 complete example — Linux host.
//
// Calls every MS5611Full method once.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x77"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x77) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := pressure.NewMS5611Full(conn) // Create MS5611 driver, (connection) → *MS5611Full
	chk(chip.Init())                     // Reset, read PROM, verify CRC-4, () → error
	t, p, err := chip.Read()             // Read temperature and pressure, () → (float32 °C, float32 Pa, error)
	chk(err)                             // OSR 4096, second-order compensation applied
	fmt.Printf("%.2f C, %.1f Pa\n", t, p)
	pp, err := chip.ReadPressure() // Read pressure only, () → (float32 Pa, error)
	chk(err)
	fmt.Printf("pressure %.1f Pa\n", pp)
	tt, err := chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	chk(err)                          // converts D2 only
	fmt.Printf("temperature %.2f C\n", tt)
	d1, d2, err := chip.ReadRaw() // Read raw ADC codes, () → (uint32, uint32, error)
	chk(err)                      // (d1 pressure, d2 temperature), 24-bit
	fmt.Printf("raw %d %d\n", d1, d2)
	t, p = chip.Compensate(d1, d2, true)             // Compensate raw codes, (d1, d2, secondOrder) → (float32 °C, float32 Pa)
	fmt.Printf("compensated %.2f C %.1f Pa\n", t, p) // pure computation, no bus access
	t, p = chip.Compensate(d1, d2, false)
	fmt.Printf("first order %.2f C %.1f Pa\n", t, p)
	chk(chip.SetOversampling(1024))         // Select oversampling ratio, (osr) → error; 256, 512, 1024, 2048 or 4096
	fmt.Println("osr", chip.Oversampling()) // Current oversampling ratio, () → uint16
	ct, err := chip.ConversionTime(0)       // Max conversion time, (osr; 0 = current) → (time.Duration, error)
	chk(err)
	fmt.Println("conversion time", ct)
	chk(chip.StartConversion(pressure.MS5611Temperature, 0)) // Start a conversion, (kind, osr) → error; non-blocking
	time.Sleep(ct + time.Millisecond)
	adc, err := chip.ReadADC() // Read the ADC result, () → (uint32, error); only after the conversion time
	chk(err)
	fmt.Println("adc", adc)
	chk(chip.SetOversampling(4096))
	prom, err := chip.ReadPROM() // Read the PROM words, () → ([8]uint16, error)
	chk(err)
	fmt.Println("prom", prom)
	fmt.Println("coefficients", chip.Coefficients()) // Calibration coefficients, () → [6]uint16; C1 .. C6
	fmt.Println("crc ok", chip.PROMCRCOK())          // Verify the PROM CRC-4, () → bool
	fmt.Println("serial", chip.SerialCode())         // Serial code, () → uint16; PROM word 7 bits 15:4
	h, err := chip.Altitude(101325)                  // Altitude from pressure, (seaLevelPa) → (float32 m, error)
	chk(err)                                         // international barometric formula
	fmt.Printf("altitude %.2f m\n", h)
	chk(chip.Reset()) // Reset and reload PROM, () → error; 0x1E, 3 ms wait
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
