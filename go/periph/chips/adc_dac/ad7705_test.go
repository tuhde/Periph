package adcdac

import (
	"testing"
)

// Reuses mockConnection, setRegister, bytesEqual from mcp4725_test.go --
// AD7705's comm byte is itself the register address (like MCP4725), so the
// shared mock's generic Write/WriteRead register-map behavior applies as-is.

type fakeOutputPin struct {
	calls []bool
}

func (p *fakeOutputPin) Set(high bool) error {
	p.calls = append(p.calls, high)
	return nil
}

func TestAD7705Init(t *testing.T) {
	// mclkHz=4915200 -> CLKDIV=1, CLK=1, FS1:FS0=00 (50 Hz) -> Clock reg = 0x0C
	// (matches the spec's own worked example). Setup reg = MODE_SELF_CAL|GAIN_1|
	// BIPOLAR|UNBUFFERED|FSYNC_RUN = 0x40.
	conn := newMockConnection()
	sensor, err := NewAD7705Minimal(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7705Minimal: %v", err)
	}
	if !bytesEqual(conn.writes[0], []byte{0x20, 0x0C}) {
		t.Errorf("init clock write: got %v", conn.writes[0])
	}
	if !bytesEqual(conn.writes[1], []byte{0x10, 0x40}) {
		t.Errorf("init setup write: got %v", conn.writes[1])
	}
	if !bytesEqual(conn.writes[2], []byte{0x08}) {
		t.Errorf("init drdy poll: got %v", conn.writes[2])
	}

	if _, err := NewAD7705Minimal(conn, 2.5, 123, nil); err == nil {
		t.Errorf("expected error for invalid mclkHz")
	}

	// ReadRaw / ReadVoltage: Channel 1, gain 1, bipolar.
	// Data Register CH1 read comm = REG_DATA|RW_READ|CH1 = 0x38.
	// code=0xC000 (49152) -> ((49152-32768)/32768)*(2.5/1) = 1.25 V
	conn.setRegister(0x38, 0xC0, 0x00)
	raw, err := sensor.ReadRaw()
	if err != nil || raw != 0xC000 {
		t.Errorf("ReadRaw: got %v, %v", raw, err)
	}
	v, err := sensor.ReadVoltage()
	if err != nil || abs(v-1.25) > 1e-9 {
		t.Errorf("ReadVoltage: got %v, %v", v, err)
	}
}

func TestAD7705ConfigureChannel2IndependentOfChannel1(t *testing.T) {
	// Regression test for a driver bug found while writing this test:
	// Configure() only updated the shared gain/bipolar/buffered fields when
	// channel==1, so ReadVoltageChannel(2) silently converted using channel
	// 1's gain/bipolar instead of channel 2's. A second, separate bug:
	// configureClock() was hardcoded to always write Channel 1's Clock
	// Register, even when configuring channel 2.
	conn := newMockConnection()
	full, err := NewAD7705Full(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7705Full: %v", err)
	}

	// Configure(2, gain=4, bipolar=false, buffered=true, 250 Hz):
	// Clock reg CH2 (comm=0x21): CLKDIV=1,CLK=1,FS=index(250)=2 -> 0x0E
	// Setup reg CH2 (comm=0x11): MODE_NORMAL|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x16
	if err := full.Configure(2, 4, false, true, 250); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	n := len(conn.writes)
	if !bytesEqual(conn.writes[n-2], []byte{0x21, 0x0E}) {
		t.Errorf("configure ch2 clock: got %v", conn.writes[n-2])
	}
	if !bytesEqual(conn.writes[n-1], []byte{0x11, 0x16}) {
		t.Errorf("configure ch2 setup: got %v", conn.writes[n-1])
	}

	// Data Register CH2 read comm = REG_DATA|RW_READ|CH2 = 0x39.
	// code=0x8000 (32768), gain=4, unipolar -> (32768/65536)*(2.5/4) = 0.3125 V
	conn.setRegister(0x39, 0x80, 0x00)
	v2, err := full.ReadVoltageChannel(2)
	if err != nil || abs(v2-0.3125) > 1e-9 {
		t.Errorf("ReadVoltageChannel(2): got %v, %v", v2, err)
	}

	// Channel 1 was never configured, so it must still use the ctor default
	// (gain 1, bipolar) -- unaffected by channel 2's Configure() above.
	conn.setRegister(0x38, 0xC0, 0x00)
	v1, err := full.ReadVoltageChannel(1)
	if err != nil || abs(v1-1.25) > 1e-9 {
		t.Errorf("ReadVoltageChannel(1): got %v, %v", v1, err)
	}

	if err := full.Configure(3, 1, true, false, 50); err == nil {
		t.Errorf("expected error for invalid channel")
	}
	if err := full.Configure(1, 3, true, false, 50); err == nil {
		t.Errorf("expected error for invalid gain")
	}
}

func TestAD7705CalibrationUsesConfiguredChannelState(t *testing.T) {
	conn := newMockConnection()
	full, err := NewAD7705Full(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7705Full: %v", err)
	}
	if err := full.Configure(2, 4, false, true, 250); err != nil {
		t.Fatalf("Configure: %v", err)
	}

	// SelfCalibrate(2): setup = MODE_SELF_CAL(0x40)|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x56
	// -- channel 2's configured state, not channel 1's defaults.
	if err := full.SelfCalibrate(2); err != nil {
		t.Fatalf("SelfCalibrate: %v", err)
	}
	n := len(conn.writes)
	if !bytesEqual(conn.writes[n-2], []byte{0x11, 0x56}) {
		t.Errorf("self_calibrate ch2 uses own state: got %v", conn.writes[n-2])
	}

	// SystemCalibrateZero(1) / SystemCalibrateFull(1): channel 1's untouched
	// defaults (gain 1, bipolar).
	if err := full.SystemCalibrateZero(1); err != nil {
		t.Fatalf("SystemCalibrateZero: %v", err)
	}
	n = len(conn.writes)
	if !bytesEqual(conn.writes[n-2], []byte{0x10, 0x80}) { // MODE_ZERO_SYS|GAIN_1|BIPOLAR
		t.Errorf("system_calibrate_zero: got %v", conn.writes[n-2])
	}

	if err := full.SystemCalibrateFull(1); err != nil {
		t.Fatalf("SystemCalibrateFull: %v", err)
	}
	n = len(conn.writes)
	if !bytesEqual(conn.writes[n-2], []byte{0x10, 0xC0}) { // MODE_FULL_SYS|GAIN_1|BIPOLAR
		t.Errorf("system_calibrate_full: got %v", conn.writes[n-2])
	}
}

func TestAD7705CalibrationRegistersAndPowerControl(t *testing.T) {
	conn := newMockConnection()
	full, err := NewAD7705Full(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7705Full: %v", err)
	}

	// Zero-Scale reg CH1 read comm = REG_OFFSET|RW_READ|CH1 = 0x68.
	conn.setRegister(0x68, 0x12, 0x34, 0x56)
	got, err := full.GetOffsetCalibration(1)
	if err != nil || got != 0x123456 {
		t.Errorf("GetOffsetCalibration: got %#x, %v", got, err)
	}

	if err := full.SetOffsetCalibration(0xABCDEF, 1); err != nil {
		t.Fatalf("SetOffsetCalibration: %v", err)
	}
	if !bytesEqual(conn.writes[len(conn.writes)-1], []byte{0x60, 0xAB, 0xCD, 0xEF}) {
		t.Errorf("SetOffsetCalibration write: got %v", conn.writes[len(conn.writes)-1])
	}

	// Full-Scale reg CH1 read comm = REG_GAIN|RW_READ|CH1 = 0x78.
	conn.setRegister(0x78, 0x01, 0x02, 0x03)
	got, err = full.GetGainCalibration(1)
	if err != nil || got != 0x010203 {
		t.Errorf("GetGainCalibration: got %#x, %v", got, err)
	}

	if err := full.SetGainCalibration(0x040506, 1); err != nil {
		t.Fatalf("SetGainCalibration: %v", err)
	}
	if !bytesEqual(conn.writes[len(conn.writes)-1], []byte{0x70, 0x04, 0x05, 0x06}) {
		t.Errorf("SetGainCalibration write: got %v", conn.writes[len(conn.writes)-1])
	}

	// Standby(): comm(COMM,WRITE,CH1)|STBY_SLEEP = 0x04.
	if err := full.Standby(); err != nil {
		t.Fatalf("Standby: %v", err)
	}
	if !bytesEqual(conn.writes[len(conn.writes)-1], []byte{0x04}) {
		t.Errorf("Standby write: got %v", conn.writes[len(conn.writes)-1])
	}

	// Wakeup(): comm(COMM,WRITE,CH1)|STBY_RUN = 0x00, then DRDY poll.
	if err := full.Wakeup(); err != nil {
		t.Fatalf("Wakeup: %v", err)
	}
	n := len(conn.writes)
	if !bytesEqual(conn.writes[n-2], []byte{0x00}) {
		t.Errorf("Wakeup clears stby: got %v", conn.writes[n-2])
	}
	if !bytesEqual(conn.writes[n-1], []byte{0x08}) {
		t.Errorf("Wakeup waits drdy: got %v", conn.writes[n-1])
	}
}

func TestAD7705Reset(t *testing.T) {
	conn := newMockConnection()
	full, err := NewAD7705Full(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7705Full: %v", err)
	}
	if err := full.Reset(); err == nil {
		t.Errorf("expected error resetting without a resetPin")
	}

	pin := &fakeOutputPin{}
	conn2 := newMockConnection()
	withReset, err := NewAD7705Full(conn2, 2.5, MCLK4_9152MHz, pin)
	if err != nil {
		t.Fatalf("NewAD7705Full with reset pin: %v", err)
	}
	if len(pin.calls) != 2 || pin.calls[0] != false || pin.calls[1] != true {
		t.Errorf("init reset pulse: got %v", pin.calls)
	}
	pin.calls = nil
	if err := withReset.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if len(pin.calls) != 2 || pin.calls[0] != false || pin.calls[1] != true {
		t.Errorf("reset pulse: got %v", pin.calls)
	}
}

func abs(f float64) float64 {
	if f < 0 {
		return -f
	}
	return f
}
