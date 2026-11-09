//go:build linux && !tinygo

// PCF85063A hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rtc"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

var passed, failed int

func check(label string, cond bool) {
	if cond {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Println("FAIL", label)
		failed++
	}
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(2)
	}
}

func done() {
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, rtc.PCF85063AAddress, nil, nil)
	must(err)
	defer conn.Close()
	clock, err := rtc.NewPCF85063AMinimal(conn)
	check("construct_minimal", err == nil)
	if err != nil {
		done()
		return
	}
	must(clock.SetDatetime(2026, 9, 23, 3, 14, 30, 0))
	year, month, day, weekday, hour, minute, _, err := clock.GetDatetime()
	check("get_datetime_roundtrip", err == nil && year == 2026 && month == 9 && day == 23 && weekday == 3 &&
		hour == 14 && (minute == 30 || minute == 31))

	full, err := rtc.NewPCF85063AFull(conn)
	check("construct_full", err == nil)
	if err != nil {
		done()
		return
	}
	stopped, err := full.OscillatorStopped()
	check("oscillator_running_after_set_datetime", err == nil && !stopped)

	alarm := rtc.PCF85063AAlarm{Second: 0, Minute: 15, Hour: 6, Day: rtc.PCF85063AAlarmDisabled, Weekday: rtc.PCF85063AAlarmDisabled}
	must(full.SetAlarm(alarm))
	got, err := full.GetAlarm()
	check("alarm_roundtrip", err == nil && got == alarm)
	must(full.SetAlarm(rtc.PCF85063AAlarm{Second: -1, Minute: -1, Hour: -1, Day: -1, Weekday: -1}))

	must(full.SetOffset(-3, rtc.PCF85063AOffsetCourse))
	offset, mode, err := full.GetOffset()
	check("offset_roundtrip", err == nil && offset == -3 && mode == rtc.PCF85063AOffsetCourse)
	must(full.SetOffset(0, rtc.PCF85063AOffsetNormal))

	must(full.WriteRAM(0xA5))
	ram, err := full.ReadRAM()
	check("ram_roundtrip", err == nil && ram == 0xA5)

	// Countdown timer at 64 Hz from 64 counts expires after ~1 s.
	_, _ = full.PollInterrupt()
	must(full.ConfigureTimer(64, rtc.PCF85063AClock64Hz, false))
	time.Sleep(1500 * time.Millisecond)
	status, err := full.PollInterrupt()
	check("timer_fires", err == nil && status&rtc.PCF85063ASourceTimer != 0)
	must(full.DisableTimer())

	done()
}
