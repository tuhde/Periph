//go:build linux && !tinygo

// SHT31 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the SHT31 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x44"), 0, 8)
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

	chip, err := humidity.NewSHT31Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

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

	t, rh, err := chip.Read()
	check("read ok", err == nil)
	check("temperature range", t > -40 && t < 125)
	check("humidity range", rh >= 0 && rh <= 100)
	tc, err := chip.ReadTemperature()
	check("read_temperature range", err == nil && tc > -40 && tc < 125)
	rhp, err := chip.ReadHumidity()
	check("read_humidity range", err == nil && rhp >= 0 && rhp <= 100)
	rawT, rawRH, err := chip.ReadRaw()
	check("read_raw ok", err == nil && rawT <= 65535 && rawRH <= 65535)

	for _, level := range []string{humidity.SHT31RepeatabilityLow, humidity.SHT31RepeatabilityMedium, humidity.SHT31RepeatabilityHigh} {
		check("set_repeatability "+level, chip.SetRepeatability(level) == nil)
		t2, rh2, err := chip.Read()
		check("repeatability read "+level, err == nil && t2 > -40 && t2 < 125 && rh2 >= 0 && rh2 <= 100)
	}

	_, err = chip.ReadStatus()
	check("status readable", err == nil)
	check("clear_status", chip.ClearStatus() == nil)
	status, err := chip.ReadStatus()
	check("status bit 4 cleared", err == nil && status&0x0010 == 0)

	check("start_periodic", chip.StartPeriodic(2, humidity.SHT31RepeatabilityHigh, false) == nil)
	time.Sleep(1200 * time.Millisecond)
	_, _, ok, err := chip.Fetch()
	check("periodic fetch", err == nil && ok)
	check("stop_periodic", chip.StopPeriodic() == nil)

	check("heater on", chip.SetHeater(true) == nil)
	status, err = chip.ReadStatus()
	check("heater bit set", err == nil && status&0x2000 != 0)
	check("heater off", chip.SetHeater(false) == nil)
	status, err = chip.ReadStatus()
	check("heater bit cleared", err == nil && status&0x2000 == 0)

	dp, err := chip.DewPoint()
	check("dew point plausible", err == nil && dp > -60 && dp < 60)
	check("soft_reset", chip.SoftReset() == nil)
	_, _, err = chip.Read()
	check("read after soft reset", err == nil)

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
