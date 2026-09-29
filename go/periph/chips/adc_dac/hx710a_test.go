package adcdac

import "testing"

func newHX710AFullSensor(t *testing.T) (*mockHX711Conn, *HX710AFull) {
	t.Helper()
	conn := newMockHX711Conn()
	conn.queueRead(0)
	d, err := NewHX710AFull(conn)
	if err != nil {
		t.Fatalf("NewHX710AFull: %v", err)
	}
	return conn, d
}

func TestHX710AMinimalInitDiscardsFirstReading(t *testing.T) {
	conn := newMockHX711Conn()
	conn.queueRead(0)
	if _, err := NewHX710AMinimal(conn); err != nil {
		t.Fatalf("NewHX710AMinimal: %v", err)
	}
	if len(conn.reads) != 1 || conn.reads[0] != 25 {
		t.Errorf("init reads = %v, want [25]", conn.reads)
	}
}

func TestHX710AMinimalReadRawUses10SPS(t *testing.T) {
	conn := newMockHX711Conn()
	conn.queueRead(0)
	d, err := NewHX710AMinimal(conn)
	if err != nil {
		t.Fatalf("NewHX710AMinimal: %v", err)
	}
	conn.queueRead(12345)
	v, err := d.ReadRaw()
	if err != nil {
		t.Fatalf("ReadRaw: %v", err)
	}
	if v != 12345 {
		t.Errorf("ReadRaw() = %v, want 12345", v)
	}
	if lastRead(conn.reads) != 25 {
		t.Errorf("last pulse count = %v, want 25", lastRead(conn.reads))
	}
}

func TestHX710AFullReadRawDefault10SPS(t *testing.T) {
	conn, d := newHX710AFullSensor(t)
	conn.queueRead(1000)
	v, err := d.ReadRaw()
	if err != nil {
		t.Fatalf("ReadRaw: %v", err)
	}
	if v != 1000 {
		t.Errorf("ReadRaw() = %v, want 1000", v)
	}
	if lastRead(conn.reads) != 25 {
		t.Errorf("last pulse count = %v, want 25", lastRead(conn.reads))
	}
}

func TestHX710AFullSetRate(t *testing.T) {
	conn, d := newHX710AFullSensor(t)

	conn.queueRead(0) // dummy read issued by SetRate(40)
	if err := d.SetRate(HX710ARate40SPS); err != nil {
		t.Fatalf("SetRate(40): %v", err)
	}
	if lastRead(conn.reads) != 27 {
		t.Errorf("SetRate(40) dummy read pulses = %v, want 27", lastRead(conn.reads))
	}
	conn.queueRead(2000)
	if v, err := d.ReadRaw(); err != nil || v != 2000 || lastRead(conn.reads) != 27 {
		t.Errorf("ReadRaw after SetRate(40) = %v, %v, pulses=%v", v, err, lastRead(conn.reads))
	}

	conn.queueRead(0) // dummy read issued by SetRate(10)
	if err := d.SetRate(HX710ARate10SPS); err != nil {
		t.Fatalf("SetRate(10): %v", err)
	}
	if lastRead(conn.reads) != 25 {
		t.Errorf("SetRate(10) dummy read pulses = %v, want 25", lastRead(conn.reads))
	}
}

func TestHX710AFullSetRateInvalid(t *testing.T) {
	_, d := newHX710AFullSensor(t)
	if err := d.SetRate(99); err == nil {
		t.Error("SetRate(99) = nil error, want an error")
	}
}

func TestHX710AFullReadAverage(t *testing.T) {
	conn, d := newHX710AFullSensor(t)
	conn.queueRead(10)
	conn.queueRead(20)
	conn.queueRead(33)
	avg, err := d.ReadAverage(3)
	if err != nil {
		t.Fatalf("ReadAverage: %v", err)
	}
	if want := int32((10 + 20 + 33) / 3); avg != want {
		t.Errorf("ReadAverage(3) = %v, want %v", avg, want)
	}
}

func TestHX710AFullTareAndReadWeight(t *testing.T) {
	conn, d := newHX710AFullSensor(t)
	conn.queueRead(100)
	conn.queueRead(100)
	if err := d.Tare(2); err != nil {
		t.Fatalf("Tare: %v", err)
	}
	if d.GetOffset() != 100 {
		t.Errorf("GetOffset() = %v, want 100", d.GetOffset())
	}

	d.SetScale(2.5)
	if d.GetScale() != 2.5 {
		t.Errorf("GetScale() = %v, want 2.5", d.GetScale())
	}

	conn.queueRead(350)
	weight, err := d.ReadWeight(1)
	if err != nil {
		t.Fatalf("ReadWeight: %v", err)
	}
	want := float32(350-100) / 2.5
	if weight != want {
		t.Errorf("ReadWeight(1) = %v, want %v", weight, want)
	}
}

// Regression: ReadTemperatureRaw() must clock exactly 26 pulses (the
// temperature channel per the HX710A pulse-count table), not 25 or 27
// (which would silently read the differential input instead).
func TestHX710AFullReadTemperatureRawUses26Pulses(t *testing.T) {
	conn, d := newHX710AFullSensor(t)
	conn.queueRead(777)
	v, err := d.ReadTemperatureRaw()
	if err != nil {
		t.Fatalf("ReadTemperatureRaw: %v", err)
	}
	if v != 777 {
		t.Errorf("ReadTemperatureRaw() = %v, want 777", v)
	}
	if lastRead(conn.reads) != 26 {
		t.Errorf("temperature reading pulses = %v, want 26", lastRead(conn.reads))
	}
}

func TestHX710AFullPowerDownPowerUp(t *testing.T) {
	conn, d := newHX710AFullSensor(t)

	conn.queueRead(0) // dummy read issued by SetRate(40)
	if err := d.SetRate(HX710ARate40SPS); err != nil {
		t.Fatalf("SetRate(40): %v", err)
	}

	if err := d.PowerDown(); err != nil {
		t.Fatalf("PowerDown: %v", err)
	}
	if lastPowerCall(conn.powerCalls) != "down" {
		t.Errorf("last power call = %q, want \"down\"", lastPowerCall(conn.powerCalls))
	}

	conn.queueRead(0) // discarded by PowerUp
	if err := d.PowerUp(); err != nil {
		t.Fatalf("PowerUp: %v", err)
	}
	if lastPowerCall(conn.powerCalls) != "up" {
		t.Errorf("last power call = %q, want \"up\"", lastPowerCall(conn.powerCalls))
	}
	if lastRead(conn.reads) != 25 {
		t.Errorf("PowerUp discard-read pulses = %v, want 25 (rate reset)", lastRead(conn.reads))
	}

	conn.queueRead(4242)
	if v, err := d.ReadRaw(); err != nil || v != 4242 || lastRead(conn.reads) != 25 {
		t.Errorf("ReadRaw after PowerUp = %v, %v, pulses=%v", v, err, lastRead(conn.reads))
	}
}
