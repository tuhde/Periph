//go:build linux && !tinygo

// DS3231 hardware test — Linux host.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, 0x68, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	clock, err := rtc.NewDS3231Minimal(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}
	check("construct_minimal", true)

	if err := clock.SetDatetime(2026, 9, 22, 2, 14, 30, 0); err != nil {
		fmt.Fprintln(os.Stderr, "set_datetime:", err)
		os.Exit(2)
	}
	check("set_datetime_no_error", true)

	year, month, day, weekday, hour, minute, _, err := clock.GetDatetime()
	check("get_datetime_no_error", err == nil)
	check("get_datetime_roundtrip",
		year == 2026 && month == 9 && day == 22 && weekday == 2 && hour == 14 && minute == 30)

	temp, err := clock.ReadTemperature()
	check("read_temperature_no_error", err == nil)
	check("read_temperature_plausible", !math.IsNaN(float64(temp)) && temp > -40 && temp < 85)

	full, err := rtc.NewDS3231Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init full:", err)
		os.Exit(2)
	}
	check("construct_full", true)

	if err := full.SetAlarm1(0, 0, 12, 0, rtc.DS3231Alarm1MatchHoursMinutesSeconds); err != nil {
		fmt.Fprintln(os.Stderr, "set_alarm1:", err)
		os.Exit(2)
	}
	a1Second, a1Minute, a1Hour, _, a1Mode, err := full.GetAlarm1()
	check("alarm1_roundtrip", err == nil && a1Second == 0 && a1Minute == 0 && a1Hour == 12 &&
		a1Mode == rtc.DS3231Alarm1MatchHoursMinutesSeconds)

	if err := full.ForceTemperatureConversion(); err != nil {
		fmt.Fprintln(os.Stderr, "force_temperature_conversion:", err)
		os.Exit(2)
	}
	check("force_temperature_conversion_no_error", true)

	offset, err := full.GetAgingOffset()
	check("get_aging_offset_no_error", err == nil)
	if err := full.SetAgingOffset(offset); err != nil {
		fmt.Fprintln(os.Stderr, "set_aging_offset:", err)
		os.Exit(2)
	}
	check("set_aging_offset_no_error", true)

	status, err := full.PollInterrupt()
	check("poll_interrupt_no_error", err == nil)
	_ = status

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
