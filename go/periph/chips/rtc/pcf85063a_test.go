package rtc

import (
	"bytes"
	"testing"
)

// newPCF85063AFull builds a PCF85063AFull over the FIFO mock, consuming the
// constructor's presence read and Control_1 read-modify-write read.
func newPCF85063AFull(t *testing.T) (*PCF85063AFull, *mockConnection) {
	t.Helper()
	conn := newMockConnection()
	conn.queueRead([]byte{0x00}) // Control_1 presence check
	conn.queueRead([]byte{0x00}) // Control_1 read-modify-write
	d, err := NewPCF85063AFull(conn)
	if err != nil {
		t.Fatalf("NewPCF85063AFull: %v", err)
	}
	return d, conn
}

func TestPCF85063AConstructForces24HourRunning(t *testing.T) {
	conn := newMockConnection()
	conn.queueRead([]byte{0x37})
	conn.queueRead([]byte{0x37}) // STOP, SR, CIE, 12_24, CAP_SEL set
	if _, err := NewPCF85063AMinimal(conn); err != nil {
		t.Fatalf("NewPCF85063AMinimal: %v", err)
	}
	// STOP and 12_24 cleared, SR masked, CIE and CAP_SEL preserved.
	if !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl1, 0x05}) {
		t.Errorf("constructor: want Control_1=0x05, got %v", conn.lastWrite())
	}
	for _, w := range conn.writes {
		if len(w) > 2 {
			t.Errorf("constructor wrote a time register: %v", w)
		}
	}
}

func TestPCF85063AGetSetDatetime(t *testing.T) {
	d, conn := newPCF85063AFull(t)

	// 2026-09-23 Wednesday(3) 14:30:45, OS flag set is masked off.
	conn.queueRead([]byte{0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26})
	year, month, day, weekday, hour, minute, second, err := d.GetDatetime()
	if err != nil {
		t.Fatalf("GetDatetime: %v", err)
	}
	if year != 2026 || month != 9 || day != 23 || weekday != 3 || hour != 14 || minute != 30 || second != 45 {
		t.Errorf("GetDatetime: got %d-%d-%d wd=%d %d:%d:%d", year, month, day, weekday, hour, minute, second)
	}

	conn.queueRead([]byte{0x02}) // Control_1 with 12_24 set
	start := len(conn.writes)
	if err := d.SetDatetime(2026, 9, 23, 3, 14, 30, 45); err != nil {
		t.Fatalf("SetDatetime: %v", err)
	}
	w := conn.writes[start:]
	want := [][]byte{
		{pcf85063aRegControl1},
		{pcf85063aRegControl1, 0x20},
		{pcf85063aRegSeconds, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26},
		{pcf85063aRegControl1, 0x00},
	}
	if len(w) != len(want) {
		t.Fatalf("SetDatetime: got %d writes %v", len(w), w)
	}
	for i := range want {
		if !bytes.Equal(w[i], want[i]) {
			t.Errorf("SetDatetime write %d: got %v, want %v", i, w[i], want[i])
		}
	}

	n := len(conn.writes)
	if err := d.SetDatetime(2100, 1, 1, 5, 0, 0, 0); err != ErrPCF85063AYear || len(conn.writes) != n {
		t.Errorf("SetDatetime 2100: want ErrPCF85063AYear and no writes, got %v (%d writes)", err, len(conn.writes)-n)
	}
}

func TestPCF85063AAlarm(t *testing.T) {
	d, conn := newPCF85063AFull(t)
	conn.queueRead([]byte{0xC8}) // Control_2: AIE, AF, TF set
	a := PCF85063AAlarm{Second: 5, Minute: 45, Hour: PCF85063AAlarmDisabled, Day: PCF85063AAlarmDisabled, Weekday: 6}
	if err := d.SetAlarm(a); err != nil {
		t.Fatalf("SetAlarm: %v", err)
	}
	if !conn.writesContain([]byte{pcf85063aRegSecondAlarm, 0x05, 0x45, 0x80, 0x80, 0x06}) {
		t.Errorf("SetAlarm: alarm registers missing: %v", conn.writes)
	}
	// AF cleared (written 0), TF kept (written 1), AIE kept.
	if !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl2, 0x88}) {
		t.Errorf("SetAlarm: want Control_2=0x88, got %v", conn.lastWrite())
	}
	conn.queueRead([]byte{0x05, 0x45, 0x80, 0x80, 0x06})
	got, err := d.GetAlarm()
	if err != nil || got != a {
		t.Errorf("GetAlarm: got %+v, %v", got, err)
	}
}

func TestPCF85063AOffset(t *testing.T) {
	d, conn := newPCF85063AFull(t)
	if err := d.SetOffset(-64, PCF85063AOffsetCourse); err != nil {
		t.Fatalf("SetOffset: %v", err)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegOffset, 0xC0}) {
		t.Errorf("SetOffset: got %v", conn.lastWrite())
	}
	conn.queueRead([]byte{0xC0})
	if off, mode, _ := d.GetOffset(); off != -64 || mode != PCF85063AOffsetCourse {
		t.Errorf("GetOffset: got %d %d", off, mode)
	}
	conn.queueRead([]byte{0x3F})
	if off, mode, _ := d.GetOffset(); off != 63 || mode != PCF85063AOffsetNormal {
		t.Errorf("GetOffset positive: got %d %d", off, mode)
	}
}

func TestPCF85063ATimer(t *testing.T) {
	d, conn := newPCF85063AFull(t)
	conn.queueRead([]byte{0x18}) // Timer_mode POR value (TE clear)
	conn.queueRead([]byte{0x18}) // Timer_mode read before the final write
	if err := d.ConfigureTimer(30, PCF85063AClock1Hz, true); err != nil {
		t.Fatalf("ConfigureTimer: %v", err)
	}
	for _, w := range [][]byte{{pcf85063aRegTimerValue, 30}, {pcf85063aRegTimerMode, 0x15}} {
		if !conn.writesContain(w) {
			t.Errorf("ConfigureTimer: missing write %v", w)
		}
	}
	// EnableInterrupt(Timer) sets TIE.
	conn.queueRead([]byte{0x15})
	if err := d.EnableInterrupt(PCF85063ASourceTimer); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegTimerMode, 0x17}) {
		t.Errorf("EnableInterrupt Timer: got %v", conn.lastWrite())
	}
	conn.queueRead([]byte{0x17})
	if err := d.DisableTimer(); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegTimerMode, 0x19}) {
		t.Errorf("DisableTimer: got %v, %v", conn.lastWrite(), err)
	}
	// ReadTimer retries until two consecutive reads agree.
	conn.queueRead([]byte{18})
	conn.queueRead([]byte{17})
	conn.queueRead([]byte{17})
	if v, err := d.ReadTimer(); err != nil || v != 17 {
		t.Errorf("ReadTimer: got %d, %v", v, err)
	}
}

func TestPCF85063AClockOutputAndMinuteInterrupt(t *testing.T) {
	d, conn := newPCF85063AFull(t)
	conn.queueRead([]byte{0x48})
	if err := d.SetClockOutput(1); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl2, 0x4E}) {
		t.Errorf("SetClockOutput: got %v, %v", conn.lastWrite(), err)
	}
	conn.queueRead([]byte{0x4E})
	if err := d.DisableClockOutput(); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl2, 0x4F}) {
		t.Errorf("DisableClockOutput: got %v, %v", conn.lastWrite(), err)
	}
	conn.queueRead([]byte{0x4F})
	if err := d.SetMinuteInterrupt(PCF85063AMinuteEvery); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl2, 0x6F}) {
		t.Errorf("SetMinuteInterrupt: got %v, %v", conn.lastWrite(), err)
	}
}

func TestPCF85063AOscillatorLoadRAMAndStop(t *testing.T) {
	d, conn := newPCF85063AFull(t)
	conn.queueRead([]byte{0x00})
	if err := d.SetOscillatorLoad(12.5); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl1, 0x01}) {
		t.Errorf("SetOscillatorLoad: got %v, %v", conn.lastWrite(), err)
	}
	conn.queueRead([]byte{0x00})
	if err := d.StopClock(); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl1, 0x20}) {
		t.Errorf("StopClock: got %v, %v", conn.lastWrite(), err)
	}
	conn.queueRead([]byte{0x20})
	if err := d.StartClock(); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl1, 0x00}) {
		t.Errorf("StartClock: got %v, %v", conn.lastWrite(), err)
	}
	if err := d.WriteRAM(0xA5); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegRAM, 0xA5}) {
		t.Errorf("WriteRAM: got %v, %v", conn.lastWrite(), err)
	}
	conn.queueRead([]byte{0xD9})
	if stopped, err := d.OscillatorStopped(); err != nil || !stopped {
		t.Errorf("OscillatorStopped: got %v, %v", stopped, err)
	}
	conn.queueRead([]byte{0xD9})
	if err := d.ClearOscillatorStop(); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegSeconds, 0x59}) {
		t.Errorf("ClearOscillatorStop: got %v, %v", conn.lastWrite(), err)
	}
}

func TestPCF85063APollInterrupt(t *testing.T) {
	d, conn := newPCF85063AFull(t)
	// AF set with AIE|MI enabled and COF=5.
	conn.queueRead([]byte{0xE5})
	status, err := d.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status != PCF85063ASourceAlarm {
		t.Errorf("PollInterrupt: got 0x%02X, want alarm", status)
	}
	// AF cleared, TF written 1, enables and COF kept.
	if !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl2, 0xAD}) {
		t.Errorf("PollInterrupt: Control_2 clear write: got %v", conn.lastWrite())
	}
	conn.queueRead([]byte{0x08})
	if status, _ := d.PollInterrupt(); status != PCF85063ASourceTimer {
		t.Errorf("PollInterrupt timer: got 0x%02X", status)
	}
	n := len(conn.writes)
	conn.queueRead([]byte{0x00})
	if status, _ := d.PollInterrupt(); status != 0 || len(conn.writes) != n+1 {
		t.Errorf("PollInterrupt none: got 0x%02X, %d extra writes", status, len(conn.writes)-n-1)
	}
}

func TestPCF85063AEnableInterruptAndSoftwareReset(t *testing.T) {
	d, conn := newPCF85063AFull(t)
	conn.queueRead([]byte{0x00}) // Control_2
	conn.queueRead([]byte{0x00}) // Control_1
	if err := d.EnableInterrupt(PCF85063ASourceAlarm | PCF85063ASourceMinute | PCF85063ASourceCorrection); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if !conn.writesContain([]byte{pcf85063aRegControl2, 0xE8}) {
		t.Errorf("EnableInterrupt: want Control_2=0xE8 (AIE|MI + AF|TF kept): %v", conn.writes)
	}
	if !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl1, 0x04}) {
		t.Errorf("EnableInterrupt: want Control_1=0x04 (CIE), got %v", conn.lastWrite())
	}
	if err := d.SoftwareReset(); err != nil || !bytes.Equal(conn.lastWrite(), []byte{pcf85063aRegControl1, 0x58}) {
		t.Errorf("SoftwareReset: got %v, %v", conn.lastWrite(), err)
	}
}
