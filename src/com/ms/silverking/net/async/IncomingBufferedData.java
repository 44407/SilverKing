package com.ms.silverking.net.async;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

import com.ms.silverking.log.Log;
import com.ms.silverking.numeric.NumConversion;
import com.ms.silverking.text.StringUtil;

/**
 * This class is designed to receive data organized into ByteBuffers.
 * It first receives metadata regarding the buffers to be received,
 * after which it receives the buffers themselves.
 * <p>
 * Header format <fieldName> (<number of bytes>):
 * numberOfBuffers    (4)
 * <p>
 * bufferLength[0]    (4)
 * ...
 * bufferLength[numberOfBuffers - 1] (4)
 * <p>
 * buffer[0]
 * buffer[1]
 * ...
 */
public final class IncomingBufferedData {
  private final boolean isClient;
  private final ByteBuffer preambleBuffer;
  private final ByteBuffer headerLengthBuffer;
  private final IntBuffer headerLengthBufferInt;
  private ByteBuffer bufferLengthsBuffer;
  private IntBuffer bufferLengthsBufferInt;
  private int curBufferIndex;
  private ByteBuffer[] buffers;
  private int lastNumRead;
  private ReadState readState;

  private enum ReadState {
    INIT_PREAMBLE_SEARCH, PREAMBLE_SEARCH, HEADER_LENGTH, BUFFER_LENGTHS, BUFFERS, DONE, CHANNEL_CLOSED
  }

  ;

  public enum ReadResult {CHANNEL_CLOSED, COMPLETE, INCOMPLETE}

  ;

  //private static final int maxBufferSize = Integer.MAX_VALUE >> 2;
  private static final int maxBufferSize = 1024 * 1024 * 1024;
  private static final int errorTolerance = 4;

  public static final byte[] preamble = { (byte) 0xab, (byte) 0xad };
  private static final byte[] clientPreamble = { (byte) 0xfe, (byte) 0xed };
  private final byte[] _preamble;

  private static final int maxNumBuffers = 2048;
  private static final int minNumBuffers = 1;

  private final boolean debug = false;

  public static void setClient() {
    //isClient = true;
  }

  public IncomingBufferedData(boolean debug, boolean isClient) {
    this.isClient = isClient;
    if (!isClient) {
      _preamble = preamble;
    } else {
      _preamble = clientPreamble;
    }
    preambleBuffer = ByteBuffer.allocate(_preamble.length);
    headerLengthBuffer = ByteBuffer.allocate(NumConversion.BYTES_PER_INT);
    headerLengthBufferInt = headerLengthBuffer.asIntBuffer();
    readState = ReadState.INIT_PREAMBLE_SEARCH;
    //this.debug = debug;
  }

  public ByteBuffer[] getBuffers() {
    return buffers;
  }

  public int getLastNumRead() {
    return lastNumRead;
  }

  public ReadResult readFromChannel(SocketChannel channel) throws IOException {
    int numRead;
    int readErrors;

    readErrors = 0;
    lastNumRead = 0;
    do {
      if (debug) {
        Log.fine(readState);
      }
      try {
        switch (readState) {
        case INIT_PREAMBLE_SEARCH:
          if (debug) {
            Log.fine("preambleBuffer.clear()");
          }
          preambleBuffer.clear();
          readState = ReadState.PREAMBLE_SEARCH;
          break;
        case PREAMBLE_SEARCH:
          numRead = channel.read(preambleBuffer);
          if (debug) {
            Log.fine(numRead + "\t" + preambleBuffer.hasRemaining() + "\t" + StringUtil.byteArrayToHexString(
                preambleBuffer.array()));
          }
          if (numRead < 0) {
            return ReadResult.CHANNEL_CLOSED;
          }
          lastNumRead += numRead;
          if (preambleBuffer.hasRemaining()) {
            return ReadResult.INCOMPLETE;
          } else {
            byte[] candidatePreamble;

            // we have a full preamble buffer, see if we match
            candidatePreamble = preambleBuffer.array();
            if (Arrays.equals(_preamble, candidatePreamble)) {
              readState = ReadState.HEADER_LENGTH;
            } else {
              // mismatch - search for real preamble
              preambleBuffer.clear();
              if (candidatePreamble[1] == preamble[0]) {
                preambleBuffer.put(preamble[0]);
              }
            }
          }
          break;
        case HEADER_LENGTH:
          if (!isClient) {
            numRead = channel.read(headerLengthBuffer);
            if (debug) {
              Log.fine("numRead ", numRead);
            }
            if (numRead < 0) {
              return ReadResult.CHANNEL_CLOSED;
            }
            lastNumRead += numRead;
            if (headerLengthBuffer.hasRemaining()) {
              return ReadResult.INCOMPLETE;
            } else {
              allocateBufferLengthsBuffer(headerLengthBufferInt.get(0));
              readState = ReadState.BUFFER_LENGTHS;
              break;
            }
          } else {
            allocateBufferLengthsBuffer(1);
          }
        case BUFFER_LENGTHS:
          if (bufferLengthsBuffer.remaining() <= 0) {
            throw new IOException("bufferLengthsBuffer.remaining() <= 0");
          }
          numRead = channel.read(bufferLengthsBuffer);
          if (debug) {
            Log.fine("numRead ", numRead);
          }
          if (numRead < 0) {
            return ReadResult.CHANNEL_CLOSED;
          } else if (numRead == 0) {
            return ReadResult.INCOMPLETE;
          } else {
            lastNumRead += numRead;
            if (bufferLengthsBuffer.remaining() == 0) {
              allocateBuffers();
              readState = ReadState.BUFFERS;
            }
            break;
          }
        case BUFFERS:
          ByteBuffer curBuffer;

          curBuffer = buffers[curBufferIndex];
          //if (curBuffer.remaining() <= 0) {
          //    throw new IOException("curBuffer.remaining() <= 0");
          //}
          if (curBuffer.remaining() > 0) {
            numRead = channel.read(curBuffer);
          } else {
            numRead = 0;
          }
          if (debug) {
            Log.fine("numRead ", numRead);
          }
          if (numRead < 0) {
            return ReadResult.CHANNEL_CLOSED;
          } else if (numRead == 0) {
            if (curBuffer.remaining() > 0) {
              return ReadResult.INCOMPLETE;
            } else {
              curBufferIndex++;
              assert curBufferIndex <= buffers.length;
              if (curBufferIndex == buffers.length) {
                readState = ReadState.DONE;
                return ReadResult.COMPLETE;
              } else {
                break;
              }
            }
          } else {
            lastNumRead += numRead;
            if (curBuffer.remaining() == 0) {
              curBufferIndex++;
              assert curBufferIndex <= buffers.length;
              if (curBufferIndex == buffers.length) {
                readState = ReadState.DONE;
                return ReadResult.COMPLETE;
              } else {
                break;
              }
            } else {
              break;
            }
          }
        case DONE:
          Log.info("IncomingBufferedData.DONE");
          return ReadResult.COMPLETE;
        case CHANNEL_CLOSED:
          throw new IOException("Channel closed");
        default:
          throw new RuntimeException("panic");
        }
      } catch (IOException ioe) {
        if (debug) {
          Log.logErrorWarning(ioe);
        }
        if (ioe.getMessage().startsWith("Connection reset")) {
          readState = ReadState.CHANNEL_CLOSED;
          return ReadResult.CHANNEL_CLOSED;
        } else {
          readErrors++;
          if (readErrors <= errorTolerance) {
            Log.logErrorWarning(ioe, "Ignoring read error " + readErrors);
            preambleBuffer.clear();
            readState = ReadState.INIT_PREAMBLE_SEARCH;
            return ReadResult.INCOMPLETE;
          } else {
            throw ioe;
          }
        }
      }
    } while (true);
  }

  private void allocateBufferLengthsBuffer(int numBuffers) throws IOException {
    if (debug) {
      Log.fine("allocateBufferLengthsBuffer ", numBuffers);
    }
    if (numBuffers < minNumBuffers) {
      throw new IOException("numBuffers < " + minNumBuffers);
    }
    if (numBuffers > maxNumBuffers) {
      throw new IOException("numBuffers > " + maxNumBuffers);
    }
    try {
      bufferLengthsBuffer = ByteBuffer.allocate(numBuffers * NumConversion.BYTES_PER_INT);
    } catch (OutOfMemoryError oome) {
      Log.warning("OutOfMemoryError caught in buffer allocation");
      throw new IOException("OutOfMemoryError caught in buffer allocation");
    }
    bufferLengthsBufferInt = bufferLengthsBuffer.asIntBuffer();
    buffers = new ByteBuffer[numBuffers];
  }

  private void allocateBuffers() throws IOException {
    if (debug) {
      Log.fine("allocateBuffers ", buffers.length);
    }
    for (int i = 0; i < buffers.length; i++) {
      int size;

      size = bufferLengthsBufferInt.get(i);
      if (size > maxBufferSize || size < 0) {
        throw new IOException("bad buffer size: " + size);
      }
      if (debug) {
        Log.fine("allocating buffer: ", size);
      }
      try {
        buffers[i] = ByteBuffer.allocate(size);
      } catch (OutOfMemoryError oome) {
        Log.warning("OutOfMemoryError caught in buffer allocation");
        throw new IOException("OutOfMemoryError caught in buffer allocation");
      }
    }
  }

  public String toString() {
    StringBuilder sb;

    sb = new StringBuilder();
    sb.append("*********************************\n");
    sb.append(bufferToString(headerLengthBuffer));
    sb.append(bufferToString(bufferLengthsBuffer));
    sb.append("buffers.length\t" + buffers.length);
    for (ByteBuffer buffer : buffers) {
      sb.append(bufferToString(buffer));
    }
    sb.append(lastNumRead + "\t");
    sb.append(lastNumRead);
    sb.append(readState);
    sb.append("\n*********************************\n");
    return sb.toString();
  }

  public String bufferToString(ByteBuffer buf) {
    StringBuilder sb;
    ByteBuffer dup;

    dup = buf.duplicate();
    dup.rewind();
    sb = new StringBuilder();
    sb.append('[');
    sb.append(dup.limit());
    sb.append('\t');
    while (dup.remaining() > 0) {
      byte b;

      b = dup.get();
      sb.append(Integer.toHexString(b) + ":");
    }
    sb.append(']');
    return sb.toString();
  }
}
