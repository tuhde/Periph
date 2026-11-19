//go:build linux && !tinygo

// AS7262 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the AS7262 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/color"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x49"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	chip, err := color.NewAS7262Full(conn)
	if err != nil {
		fmt.Println("FAIL init:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}

	dt, err := chip.DeviceType()
	check("device type", err == nil && dt == 0x40)
	ht, hv, err := chip.HardwareVersion()
	check("hardware version", err == nil && ht == 0x40 && hv == 0x3E)
	temp, err := chip.Temperature()
	check("temperature range", err == nil && temp >= -40 && temp <= 125)

	d, err := chip.Read()
	check("calibrated channels", err == nil && d.Violet >= 0 && d.Red >= 0)
	r, err := chip.ReadRaw()
	check("raw channels", err == nil && len(r.Array()) == 6)
	raw2, err := chip.ReadChannelRaw(2)
	check("read_channel_raw", err == nil && raw2 == raw2)

	check("set gain 64", chip.SetGain(64) == nil)
	g, err := chip.GetGain()
	check("gain", err == nil && g == 64)
	check("set gain 16", chip.SetGain(16) == nil)
	check("set integration time", chip.SetIntegrationTime(50) == nil)
	ms, err := chip.GetIntegrationTime()
	check("integration time", err == nil && ms > 50.3 && ms < 50.5)
	check("set mode 2", chip.SetMode(2) == nil)
	m, err := chip.GetMode()
	check("mode", err == nil && m == 2)
	check("set mode 3", chip.SetMode(3) == nil)

	check("measure", chip.Measure() == nil)
	time.Sleep(600 * time.Millisecond)
	ok, err := chip.DataReady()
	check("data ready", err == nil && ok)
	_, err = chip.ReadCalibrated()
	check("last calibrated", err == nil)

	check("indicator on", chip.SetIndicatorLED(true, 4) == nil)
	check("indicator off", chip.SetIndicatorLED(false, 1) == nil)
	check("soft reset", chip.SoftReset() == nil)
	ms, err = chip.GetIntegrationTime()
	check("reset int_t", err == nil && ms > 700)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
