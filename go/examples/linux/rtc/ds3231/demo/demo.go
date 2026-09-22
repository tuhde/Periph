//go:build linux && !tinygo

// DS3231 demo — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"sync"
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

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, 0x68, nil, nil)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	clock, err := rtc.NewDS3231Full(conn) // Create DS3231 Full driver, (conn) → (*DS3231Full, error)
	if err != nil {
		panic(err)
	}

	// --- Backup-clock module for a data logger ---
	// If the oscillator-stop flag is set, the chip either just powered up
	// for the first time or lost its time reference (e.g. a dead coin
	// cell that was just replaced) — its clock/calendar registers cannot
	// be trusted. Reseed from a known reference timestamp rather than
	// logging garbage; SetDatetime also clears OSF as a side effect.
	stopped, err := clock.OscillatorStopped() // Read oscillator-stop flag, () → (bool, error)
	if err != nil {
		panic(err)
	}
	if stopped {
		if err := clock.SetDatetime(2026, 9, 22, 2, 12, 0, 0); err != nil { // Set clock/calendar, (year, month, day, weekday, hour, minute, second) → error
			panic(err)
		}
	}

	// --- Arm two periodic alarms: a minute heartbeat and an hourly log ---
	// Alarm 1 matches every minute (at :00 seconds); Alarm 2 matches every
	// hour (at :00 minutes). Both repeat automatically — their registers
	// are never rewritten — so arming them once is enough for the whole
	// run.
	if err := clock.SetAlarm1(0, 0, 0, 0, rtc.DS3231Alarm1MatchSeconds); err != nil { // Set alarm 1, (second, minute, hour, day_or_date, mode) → error
		panic(err)
	}
	if err := clock.SetAlarm2(0, 0, 0, rtc.DS3231Alarm2EveryMinute); err != nil { // Set alarm 2, (minute, hour, day_or_date, mode) → error
		panic(err)
	}
	if err := clock.EnableInterrupt(rtc.DS3231SourceAlarm1); err != nil { // Enable alarm interrupt, (source) → error
		panic(err)
	}
	if err := clock.EnableInterrupt(rtc.DS3231SourceAlarm2); err != nil { // Enable alarm interrupt, (source) → error
		panic(err)
	}

	// --- Subscribe and log an entry for each alarm that fires ---
	// The callback masks the returned status against SOURCE_ALARM1 /
	// SOURCE_ALARM2 and, for each source that matched, prints a one-line
	// "log entry": current date/time plus the on-chip temperature.
	var mu sync.Mutex
	minuteAlarms := 0
	done := make(chan struct{})

	if err := clock.OnInterrupt(func(status uint8) { // Subscribe to alarm interrupts, (callback) → error
		year, month, day, weekday, hour, minute, second, err := clock.GetDatetime()
		if err != nil {
			return
		}
		temp, err := clock.ReadTemperature()
		if err != nil {
			return
		}
		if status&rtc.DS3231SourceAlarm1 != 0 {
			fmt.Printf("[alarm1] %04d-%02d-%02d (wd=%d) %02d:%02d:%02d  %.2f C\n",
				year, month, day, weekday, hour, minute, second, temp)
			mu.Lock()
			minuteAlarms++
			n := minuteAlarms
			mu.Unlock()
			if n >= 5 {
				close(done)
			}
		}
		if status&rtc.DS3231SourceAlarm2 != 0 {
			fmt.Printf("[alarm2] %04d-%02d-%02d (wd=%d) %02d:%02d:%02d  %.2f C\n",
				year, month, day, weekday, hour, minute, second, temp)
		}
	}); err != nil {
		panic(err)
	}

	// Run until 5 minute-alarms have logged, then tear down.
	select {
	case <-done:
	case <-time.After(6 * time.Minute):
	}

	if err := clock.DisableInterrupt(rtc.DS3231SourceAlarm1); err != nil { // Disable alarm interrupt, (source) → error
		panic(err)
	}
	if err := clock.DisableInterrupt(rtc.DS3231SourceAlarm2); err != nil { // Disable alarm interrupt, (source) → error
		panic(err)
	}
	if err := clock.OffInterrupt(); err != nil { // Unsubscribe, () → error
		panic(err)
	}
}
