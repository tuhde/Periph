//go:build linux && !tinygo

// MS5607 demo — Linux host.
//
// Barometric altimeter and variometer, oversampling comparison and the second-order correction.
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

	chip := pressure.NewMS5607Full(conn) // Create MS5607 driver, (connection) → *MS5607Full
	chk(chip.Init())                     // Reset, read PROM, verify CRC-4, () → error
	// --- Calibration data ---
	// Six coefficients and a CRC-4 are factory-programmed into the PROM.
	prom, err := chip.ReadPROM() // Read the PROM words, () → ([8]uint16, error)
	chk(err)
	fmt.Println("PROM", prom)
	fmt.Println("CRC ok", chip.PROMCRCOK()) // Verify the PROM CRC-4, () → bool
	fmt.Println("coefficients", chip.Coefficients(), "serial", chip.SerialCode())

	// --- Barometric altimeter / variometer: 20 s, one reading per second ---
	reference, err := chip.ReadPressure() // Read pressure only, () → (float32 Pa, error)
	chk(err)
	var previous float32
	lowest := float32(100)
	for i := 0; i < 20; i++ {
		t, p, err := chip.Read() // Read temperature and pressure, () → (float32 °C, float32 Pa, error)
		chk(err)
		if t < lowest {
			lowest = t
		}
		height, err := chip.Altitude(reference) // Altitude from pressure, (seaLevelPa) → (float32 m, error)
		chk(err)
		climb := float32(0)
		if i > 0 {
			climb = height - previous
		}
		previous = height
		fmt.Printf("%.2f C  %.1f Pa  %.2f m  %+.2f m/s\n", t, p, height, climb)
		time.Sleep(time.Second)
	}

	// --- Noise and time versus oversampling: ten readings each ---
	for _, osr := range []uint16{256, 512, 1024, 2048, 4096} {
		chk(chip.SetOversampling(osr)) // Select oversampling ratio, (osr) → error
		start := time.Now()
		lo, hi := float32(1e9), float32(-1e9)
		for n := 0; n < 10; n++ {
			v, err := chip.ReadPressure()
			chk(err)
			if v < lo {
				lo = v
			}
			if v > hi {
				hi = v
			}
		}
		fmt.Printf("OSR %4d  %v/read  spread %.1f Pa\n", osr, time.Since(start)/10, hi-lo)
	}
	chk(chip.SetOversampling(4096))

	// --- Raw codes with and without the second-order correction ---
	d1, d2, err := chip.ReadRaw() // Read raw ADC codes, () → (uint32, uint32, error)
	chk(err)
	fmt.Println("raw", d1, d2)
	t1, p1 := chip.Compensate(d1, d2, false) // Compensate raw codes, (d1, d2, secondOrder) → (float32 °C, float32 Pa)
	t2, p2 := chip.Compensate(d1, d2, true)
	fmt.Printf("first order  %.2f C %.1f Pa\n", t1, p1)
	fmt.Printf("second order %.2f C %.1f Pa\n", t2, p2)
	fmt.Printf("lowest temperature seen %.2f C\n", lowest)

	// --- Non-blocking conversion, then reset ---
	chk(chip.StartConversion(pressure.MS5607Pressure, 0)) // Start a conversion, (kind, osr) → error
	time.Sleep(12 * time.Millisecond)
	adc, err := chip.ReadADC() // Read the ADC result, () → (uint32, error)
	chk(err)
	fmt.Println("D1", adc)
	chk(chip.Reset()) // Reset and reload PROM, () → error
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
