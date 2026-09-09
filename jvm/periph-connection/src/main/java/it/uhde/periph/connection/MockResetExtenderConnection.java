package it.uhde.periph.connection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link MockConnection} that also implements {@link ResetExtender}, for unit
 * tests of chip drivers (e.g. SK6812RGBW) that request an extended reset
 * pulse. Every {@link #writeExt} call is logged to {@link #extWrites()} /
 * {@link #extResetBytes()} in addition to the base {@link #writes()} log, so
 * tests can assert both the transmitted payload and the requested reset
 * length.
 */
public class MockResetExtenderConnection extends MockConnection implements ResetExtender {

    private final List<byte[]> extWrites = new ArrayList<>();
    private final List<Integer> extResetBytes = new ArrayList<>();

    /** Log of every {@link #writeExt} payload, in call order. */
    public List<byte[]> extWrites() {
        return extWrites;
    }

    /** Log of every {@link #writeExt} requested reset length, in call order. */
    public List<Integer> extResetBytes() {
        return extResetBytes;
    }

    @Override
    public void writeExt(byte[] data, int resetBytes) throws IOException {
        extWrites.add(data.clone());
        extResetBytes.add(resetBytes);
        write(data);
    }
}
