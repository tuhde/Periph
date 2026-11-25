//go:build linux && !tinygo

// Si7021 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the Si7021 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
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

	chip := humidity.NewSi7021Full(conn)

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	check("software_reset", chip.SoftReset() == nil)
	dt, err := chip.DeviceType()
	check("device type", err == nil && dt == 0x15)
	sn, err := chip.SerialNumber()
	check("serial number", err == nil && sn != 0)
	reg, err := chip.ReadUserRegister()
	check("user register after reset", err == nil && reg&0xBF == 0x3A)

	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("temperature range", t > -40 && t < 125)
	check("humidity range", rh >= 0 && rh <= 100)
	rhp, err := chip.ReadHumidity()
	check("read_humidity range", err == nil && rhp >= 0 && rhp <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature range", err == nil && tc > -40 && tc < 125)
	tp, err := chip.ReadTemperaturePrevious()
	check("read_temperature_previous range", err == nil && tp > -40 && tp < 125)
	_, err = chip.ReadRawHumidity()
	check("raw humidity ok", err == nil)
	_, err = chip.ReadRawTemperature()
	check("raw temperature ok", err == nil)

	for _, p := range [][2]int{{12, 14}, {8, 12}, {10, 13}, {11, 11}} {
		check("set_resolution", chip.SetResolution(p[0], p[1]) == nil)
		rb, tb, err := chip.GetResolution()
		check("get_resolution", err == nil && rb == p[0] && tb == p[1])
		_, _, err = chip.Read()
		check("read at resolution", err == nil)
	}
	check("set_resolution default", chip.SetResolution(12, 14) == nil)
	ct, err := chip.ConversionTime()
	check("conversion time", err == nil && ct > 0.005 && ct < 0.03)

	check("heater on", chip.SetHeater(true) == nil)
	on, err := chip.IsHeaterOn()
	check("heater on readback", err == nil && on)
	check("heater off", chip.SetHeater(false) == nil)
	on, err = chip.IsHeaterOn()
	check("heater off readback", err == nil && !on)
	low, err := chip.VDDLow()
	check("vdd ok", err == nil && !low)
	_, err = chip.FirmwareRevision()
	check("firmware readable", err == nil)

	dp, err := chip.DewPoint()
	check("dew point plausible", err == nil && dp > -60 && dp < 60)
	check("software_reset", chip.SoftReset() == nil)
	_, _, err = chip.Read()
	check("read after software reset", err == nil)

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
