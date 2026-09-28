package adcdac

import "testing"

func TestAD7706Init(t *testing.T) {
	// mclkHz=4915200 -> CLKDIV=1, CLK=1, FS1:FS0=00 (50 Hz) -> Clock reg = 0x0C.
	// Setup reg = MODE_SELF_CAL|GAIN_1|BIPOLAR|UNBUFFERED|FSYNC_RUN = 0x40.
	conn := newMockConnection()
	sensor, err := NewAD7706Minimal(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7706Minimal: %v", err)
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

	if _, err := NewAD7706Minimal(conn, 2.5, 123, nil); err == nil {
		t.Errorf("expected error for invalid mclkHz")
	}

	// ReadRaw / ReadVoltage: Channel 1, gain 1, bipolar.
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

func TestAD7706ThreeChannelsIndependentState(t *testing.T) {
	// Regression test for the same bug class as AD7705: Configure() only
	// updated the shared gain/bipolar/buffered fields for channel 1, and
	// configureClock() was hardcoded to always write Channel 1's Clock
	// Register. AD7706 has three channels, so this also checks channel 3
	// (comm select bits 11, not just channel 2's 01).
	conn := newMockConnection()
	full, err := NewAD7706Full(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7706Full: %v", err)
	}

	// Configure(2, gain=4, bipolar=false, buffered=true, 250 Hz):
	// Clock reg CH2 (comm=0x21) -> 0x0E, Setup reg CH2 (comm=0x11) -> 0x16.
	if err := full.Configure(2, 4, false, true, 250); err != nil {
		t.Fatalf("Configure(2): %v", err)
	}
	n := len(conn.writes)
	if !bytesEqual(conn.writes[n-2], []byte{0x21, 0x0E}) {
		t.Errorf("configure ch2 clock: got %v", conn.writes[n-2])
	}
	if !bytesEqual(conn.writes[n-1], []byte{0x11, 0x16}) {
		t.Errorf("configure ch2 setup: got %v", conn.writes[n-1])
	}

	// Configure(3, gain=8, bipolar=true, buffered=false, 500 Hz):
	// Channel 3 select = CH1:CH0=11 -> ch3=0x03.
	// Clock reg CH3 (comm=0x23): CLKDIV=1,CLK=1,FS=index(500)=3 -> 0x0F
	// Setup reg CH3 (comm=0x13): MODE_NORMAL|GAIN_8(0x18)|BIPOLAR|UNBUFFERED = 0x18
	if err := full.Configure(3, 8, true, false, 500); err != nil {
		t.Fatalf("Configure(3): %v", err)
	}
	n = len(conn.writes)
	if !bytesEqual(conn.writes[n-2], []byte{0x23, 0x0F}) {
		t.Errorf("configure ch3 clock: got %v", conn.writes[n-2])
	}
	if !bytesEqual(conn.writes[n-1], []byte{0x13, 0x18}) {
		t.Errorf("configure ch3 setup: got %v", conn.writes[n-1])
	}

	// Data Register reads: CH2 comm=0x39, CH3 comm=0x3B.
	// CH2: code=0x8000, gain=4, unipolar -> (32768/65536)*(2.5/4) = 0.3125 V
	conn.setRegister(0x39, 0x80, 0x00)
	v2, err := full.ReadVoltageChannel(2)
	if err != nil || abs(v2-0.3125) > 1e-9 {
		t.Errorf("ReadVoltageChannel(2): got %v, %v", v2, err)
	}
	// CH3: code=0xE000 (57344), gain=8, bipolar -> ((57344-32768)/32768)*(2.5/8) = 0.234375 V
	conn.setRegister(0x3B, 0xE0, 0x00)
	v3, err := full.ReadVoltageChannel(3)
	if err != nil || abs(v3-0.234375) > 1e-9 {
		t.Errorf("ReadVoltageChannel(3): got %v, %v", v3, err)
	}

	// Channel 1 was never configured -> still the ctor default (gain 1, bipolar).
	conn.setRegister(0x38, 0xC0, 0x00)
	v1, err := full.ReadVoltageChannel(1)
	if err != nil || abs(v1-1.25) > 1e-9 {
		t.Errorf("ReadVoltageChannel(1): got %v, %v", v1, err)
	}

	if err := full.Configure(4, 1, true, false, 50); err == nil {
		t.Errorf("expected error for invalid channel")
	}
}

func TestAD7706CalibrationUsesConfiguredChannelState(t *testing.T) {
	conn := newMockConnection()
	full, err := NewAD7706Full(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7706Full: %v", err)
	}
	if err := full.Configure(3, 8, true, false, 500); err != nil {
		t.Fatalf("Configure: %v", err)
	}

	// SelfCalibrate(3): setup = MODE_SELF_CAL(0x40)|GAIN_8(0x18)|BIPOLAR|UNBUFFERED = 0x58
	// -- channel 3's configured state, not channel 1's defaults.
	if err := full.SelfCalibrate(3); err != nil {
		t.Fatalf("SelfCalibrate: %v", err)
	}
	n := len(conn.writes)
	if !bytesEqual(conn.writes[n-2], []byte{0x13, 0x58}) {
		t.Errorf("self_calibrate ch3 uses own state: got %v", conn.writes[n-2])
	}
}

func TestAD7706CalibrationRegistersAndPowerControl(t *testing.T) {
	conn := newMockConnection()
	full, err := NewAD7706Full(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7706Full: %v", err)
	}

	// Zero-Scale reg CH3 read comm = REG_OFFSET|RW_READ|CH3(0x03) = 0x6B.
	conn.setRegister(0x6B, 0x12, 0x34, 0x56)
	got, err := full.GetOffsetCalibration(3)
	if err != nil || got != 0x123456 {
		t.Errorf("GetOffsetCalibration(3): got %#x, %v", got, err)
	}

	if err := full.SetOffsetCalibration(0xABCDEF, 3); err != nil {
		t.Fatalf("SetOffsetCalibration: %v", err)
	}
	if !bytesEqual(conn.writes[len(conn.writes)-1], []byte{0x63, 0xAB, 0xCD, 0xEF}) {
		t.Errorf("SetOffsetCalibration write: got %v", conn.writes[len(conn.writes)-1])
	}

	// Standby / Wakeup: channel-1-only, comm(COMM,WRITE,CH1) = 0x00.
	if err := full.Standby(); err != nil {
		t.Fatalf("Standby: %v", err)
	}
	if !bytesEqual(conn.writes[len(conn.writes)-1], []byte{0x04}) {
		t.Errorf("Standby write: got %v", conn.writes[len(conn.writes)-1])
	}

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

func TestAD7706Reset(t *testing.T) {
	conn := newMockConnection()
	full, err := NewAD7706Full(conn, 2.5, MCLK4_9152MHz, nil)
	if err != nil {
		t.Fatalf("NewAD7706Full: %v", err)
	}
	if err := full.Reset(); err == nil {
		t.Errorf("expected error resetting without a resetPin")
	}

	pin := &fakeOutputPin{}
	conn2 := newMockConnection()
	withReset, err := NewAD7706Full(conn2, 2.5, MCLK4_9152MHz, pin)
	if err != nil {
		t.Fatalf("NewAD7706Full with reset pin: %v", err)
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
