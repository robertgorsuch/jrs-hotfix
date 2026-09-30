package com.jaspersoft.jrshotfix.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The two hashes a file is compared by: the SHA-256 of its bytes, and the SHA-256 of its bytes with
 * every CR that stands before an LF left out, so a text file saved with Windows line ends hashes as
 * the same text. Invariants: bytes are streamed, never held whole; a CR that is not followed by an
 * LF is part of the text; the two hashes are equal exactly when the file holds no CR LF pair.
 */
public record Sums(String sha256, String textSha256, long size) {

  /** The sums of everything read from {@code in}; {@code in} is not closed. */
  public static Sums of(InputStream in) throws IOException {
    Sink sink = new Sink(OutputStream.nullOutputStream());
    in.transferTo(sink);
    return sink.sums();
  }

  public static Sums of(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return of(in);
    }
  }

  public static Sums of(byte[] bytes) {
    Sink sink = new Sink(OutputStream.nullOutputStream());
    sink.sum(bytes, 0, bytes.length);
    return sink.sums();
  }

  /**
   * An output stream that sums what is written through it and passes the bytes on to {@code copy}.
   * Closing it closes {@code copy}.
   */
  public static final class Sink extends OutputStream {
    private final MessageDigest raw = newDigest();
    private final MessageDigest text = newDigest();
    private final OutputStream copy;
    private boolean pendingCr;
    private long size;

    public Sink(OutputStream copy) {
      this.copy = copy;
    }

    @Override
    public void write(int b) throws IOException {
      byte[] one = {(byte) b};
      sum(one, 0, 1);
      copy.write(b);
    }

    @Override
    public void write(byte[] bytes, int off, int len) throws IOException {
      sum(bytes, off, len);
      copy.write(bytes, off, len);
    }

    private void sum(byte[] bytes, int off, int len) {
      raw.update(bytes, off, len);
      size += len;
      int from = off;
      for (int i = off; i < off + len; i++) {
        byte b = bytes[i];
        if (pendingCr) {
          pendingCr = false;
          if (b != '\n') {
            text.update((byte) '\r');
          }
        }
        if (b == '\r') {
          text.update(bytes, from, i - from);
          from = i + 1;
          pendingCr = true;
        }
      }
      text.update(bytes, from, off + len - from);
    }

    @Override
    public void flush() throws IOException {
      copy.flush();
    }

    @Override
    public void close() throws IOException {
      copy.close();
    }

    /** The sums of what was written so far; call it once, after the last write. */
    public Sums sums() {
      if (pendingCr) {
        pendingCr = false;
        text.update((byte) '\r');
      }
      HexFormat hex = HexFormat.of();
      return new Sums(hex.formatHex(raw.digest()), hex.formatHex(text.digest()), size);
    }
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
    }
  }
}
