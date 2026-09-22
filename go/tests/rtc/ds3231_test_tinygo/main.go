//go:build tinygo

// DS3231 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"math"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, cond bool) {
	if cond {
		println("PASS", label)
		passed++
	} else {
		println("FAIL", label)
		failed++
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, 0x68, nil, nil)
	defer conn.Close()

	clock, err := rtc.NewDS3231Minimal(conn)
	if err != nil {
		println("init:", err)
		return
	}
	check("construct_minimal", true)

	if err := clock.SetDatetime(2026, 9, 22, 2, 14, 30, 0); err != nil {
		println("set_datetime:", err)
		return
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
		println("init full:", err)
		return
	}
	check("construct_full", true)

	if err := full.SetAlarm1(0, 0, 12, 0, rtc.DS3231Alarm1MatchHoursMinutesSeconds); err != nil {
		println("set_alarm1:", err)
		return
	}
	a1Second, a1Minute, a1Hour, _, a1Mode, err := full.GetAlarm1()
	check("alarm1_roundtrip", err == nil && a1Second == 0 && a1Minute == 0 && a1Hour == 12 &&
		a1Mode == rtc.DS3231Alarm1MatchHoursMinutesSeconds)

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}
