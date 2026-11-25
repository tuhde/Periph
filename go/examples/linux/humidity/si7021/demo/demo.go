//go:build linux && !tinygo

// Si7021 demo example — Linux host.
//
// Condensation-aware hygrometer: device ID, 15 readings with dew point, all four
// resolutions and a 10 s heater test.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := humidity.NewSi7021Full(conn) // Create Si7021 driver, (connection) → *Si7021Full

	chk(chip.SoftReset())        // Software reset, () → error
	dt, err := chip.DeviceType() // Device type, () → (byte, error)
	chk(err)
	fmt.Printf("device type 0x%02X\n", dt)
	sn, err := chip.SerialNumber() // Electronic serial number, () → (uint64, error)
	chk(err)
	fmt.Printf("serial number 0x%016X\n", sn)
	fw, err := chip.FirmwareRevision() // Firmware revision, () → (byte, error)
	chk(err)
	fmt.Printf("firmware 0x%02X\n", fw)
	low, err := chip.VDDLow() // Supply low flag, () → (bool, error)
	chk(err)
	fmt.Println("VDD low:", low)

	// --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
	for i := 0; i < 15; i++ {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32, float32, error)
		chk(err)
		dp, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
		chk(err)
		fmt.Printf("%.2f C %.1f %%RH dew point %.2f C\n", t, rh, dp)
		time.Sleep(2 * time.Second)
	}

	// --- Value accessors and raw codes ---
	rhp, err := chip.ReadHumidity() // () → (float32 %RH, error)
	chk(err)
	tc, err := chip.ReadTemperature() // () → (float32 °C, error)
	chk(err)
	tp, err := chip.ReadTemperaturePrevious() // () → (float32 °C, error)
	chk(err)
	fmt.Printf("RH %.1f T %.2f T of last RH %.2f\n", rhp, tc, tp)
	rawRH, err := chip.ReadRawHumidity() // () → (uint16, error)
	chk(err)
	rawT, err := chip.ReadRawTemperature() // () → (uint16, error)
	chk(err)
	fmt.Println("raw", rawRH, rawT)
	chip.SetClockStretching(false)      // Use hold-master commands, (on bool)
	reg, err := chip.ReadUserRegister() // Read USER_REG_1, () → (byte, error)
	chk(err)
	chk(chip.WriteUserRegister(reg)) // Write USER_REG_1, (value byte) → error

	// --- Resolution comparison ---
	for _, p := range [][2]int{{12, 14}, {10, 13}, {11, 11}, {8, 12}} {
		chk(chip.SetResolution(p[0], p[1])) // Set resolution, (rhBits, temperatureBits int) → error
		start := time.Now()
		t, rh, err := chip.Read()
		chk(err)
		elapsed := time.Since(start)
		ct, err := chip.ConversionTime() // Typical conversion time, () → (float32 s, error)
		chk(err)
		rb, tb, err := chip.GetResolution() // Get resolution, () → (int, int, error)
		chk(err)
		fmt.Printf("%d/%d bit: %.2f C %.1f %%RH, %v (typ. %.1f ms), resolution %d/%d\n", p[0], p[1], t, rh, elapsed, ct*1000, rb, tb)
	}
	chk(chip.SetResolution(12, 14))

	// --- Heater test: 10 s ---
	rhBefore, err := chip.ReadHumidity()
	chk(err)
	tBefore, err := chip.ReadTemperature()
	chk(err)
	chk(chip.SetHeater(true)) // Switch the heater, (on bool) → error
	time.Sleep(10 * time.Second)
	on, err := chip.IsHeaterOn() // Heater state, () → (bool, error)
	chk(err)
	fmt.Println("heater on:", on)
	rhAfter, err := chip.ReadHumidity()
	chk(err)
	tAfter, err := chip.ReadTemperature()
	chk(err)
	fmt.Printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rhBefore-rhAfter, tAfter-tBefore)
	chk(chip.SetHeater(false))

}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
