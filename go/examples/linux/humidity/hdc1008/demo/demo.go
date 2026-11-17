//go:build linux && !tinygo

// HDC1008 demo example — Linux host.
//
// Humidity logger with heater self-check: five readings with dew point, 8/12 bit
// comparison and a 10 s heater test.
package main

import (
	"fmt"
	"math"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := humidity.NewHDC1008Full(conn) // Create HDC1008 driver, (connection) → *HDC1008Full

	chk(chip.Init())               // Write default configuration, () → error
	serial, err := chip.SerialID() // Read the 40-bit serial ID, () → (uint64, error)
	chk(err)
	fmt.Printf("serial id 0x%010X\n", serial)

	// --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
	for i := 0; i < 15; i++ {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32, float32, error)
		chk(err)
		if rh < 0.01 {
			rh = 0.01
		}
		g := math.Log(float64(rh)/100.0) + 17.62*float64(t)/(243.12+float64(t))
		dew := 243.12 * g / (17.62 - g) // Magnus dew point, °C
		fmt.Printf("%.2f C %.1f %%RH dew point %.2f C\n", t, rh, dew)
		time.Sleep(2 * time.Second)
	}

	// --- Heater test: 10 s ---
	rh0, err := chip.ReadHumidity() // () → (float32 %RH, error)
	chk(err)
	t0, err := chip.ReadTemperature() // () → (float32 °C, error)
	chk(err)
	chk(chip.SetHeater(true)) // Switch the heater, (on bool) → error
	time.Sleep(10 * time.Second)
	rh1, err := chip.ReadHumidity()
	chk(err)
	t1, err := chip.ReadTemperature()
	chk(err)
	fmt.Printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rh0-rh1, t1-t0)
	chk(chip.SetHeater(false))

	// --- Resolution comparison ---
	chk(chip.SetResolution(11, 8)) // Set resolution, (temperatureBits, humidityBits int) → error
	start := time.Now()
	t, rh, err := chip.Read()
	chk(err)
	fmt.Printf("11/8 bit: %.2f C %.1f %%RH in %d ms\n", t, rh, time.Since(start).Milliseconds())
	chk(chip.SetResolution(14, 14))
	low, err := chip.BatteryLow() // VDD below 2.8 V, () → (bool, error)
	chk(err)
	fmt.Println("battery low", low)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
