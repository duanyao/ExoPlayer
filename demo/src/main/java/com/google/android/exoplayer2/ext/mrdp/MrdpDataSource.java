package com.google.android.exoplayer2.ext.mrdp;

/**
 * Implement MRDP (more reliable datagram protocol).
 *
 * MRDP 是一种基于 UDP 多播的部分可靠的传输层协议。其特点是：
 * 1. 消除重复包并确保包的顺序。
 * 2. 可以用重复发送的方式降低丢包率。
 *
 * MRDP 处于 UDP 的上一层，每个 MRDP 包用一个 UDP 包承载和发送。MRDP 包有 8 字节的头部：
 *
 * 0-1 字节：固定的 ASCII 码 'mr'，作为特征码。
 * 1-2 字节：流标识（stream id），UInt16LE。流标识相同的包构成了一个“流”。
 * 3-7 字节：序列号（serial number），Int32LE，只能大于等于零。在一个流内，每个包的序列号增加1。
 *
 * 如果序列号达到 2^31 将发生回卷。
 *
 * 头部之后为载荷数据。
 *
 * MRDP 是单向发送的，无需接收端确认，也没有重传机制。接收端根据序列号可以消除重复包、纠正乱序到达的包，还可以检测到
 * 丢失了哪些包。发送端可以重复发送每个包多次（以相同的序列号），其中只要有一个到达就算成功，这样可以降低实际丢包率。
 * 例如，如果 UDP 层的丢包率是 10%，MRDP 重复发送两次的丢包率是 10% * 10% = 1%，三次则是 0.1% 。但是，WIFI 上的
 * 丢包并不总是随机的，有时在一个时间窗口（亚秒级到秒级）内密集发生，这可能造成重复发送的包有比以上计算更大的概率丢失。
 */

import android.net.Uri;
import android.os.SystemClock;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.PriorityQueue;

/**
 * Implement a {@link DataSource} for MRD (more reliable datagram) protocol
 */
public final class MrdpDataSource implements DataSource {

  /**
   * The default maximum datagram packet size, in bytes.
   */
  public static final int DEFAULT_MAX_PACKET_SIZE = 2000;

  /**
   * The default socket timeout, in milliseconds.
   */
  public static final int DEAFULT_SOCKET_TIMEOUT_MILLIS = 8 * 1000;

  public static final int DEAFULT_PACKET_TIMEOUT_MILLIS = 600;

  public static final int DEFAULT_MAX_PACKET_COUNT = 100;

  private final TransferListener listener;
  private final int socketTimeoutMillis;
  private final DatagramPacket packet;

  private Uri uri;
  private DatagramSocket socket;
  private MulticastSocket multicastSocket;
  private InetAddress address;
  private InetSocketAddress socketAddress;
  private boolean opened;

  private int packetRemaining;
  private PriorityQueue<MrdpPacket> packetQueue;
  private int lastSerialNumber;
  private int lastStreamId;
  private long lastTime;
  private int goodPacketCount;
  private int lostPacketCount;
  private int duplicatedOrLatePacketCount;

  /**
   * @param listener An optional listener.
   */
  public MrdpDataSource(TransferListener listener) {
    this(listener, DEFAULT_MAX_PACKET_SIZE);
  }

  /**
   * @param listener An optional listener.
   * @param maxPacketSize The maximum datagram packet size, in bytes.
   */
  public MrdpDataSource(TransferListener listener, int maxPacketSize) {
    this(listener, maxPacketSize, DEAFULT_SOCKET_TIMEOUT_MILLIS);
  }

  /**
   * @param listener An optional listener.
   * @param maxPacketSize The maximum datagram packet size, in bytes.
   * @param socketTimeoutMillis The socket timeout in milliseconds. A timeout of zero is interpreted
   *     as an infinite timeout.
   */
  public MrdpDataSource(TransferListener listener, int maxPacketSize, int socketTimeoutMillis) {
    this.listener = listener;
    this.socketTimeoutMillis = socketTimeoutMillis;
    packet = new DatagramPacket(new byte[maxPacketSize], 0, maxPacketSize);
    packetQueue = new PriorityQueue<>();
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    uri = dataSpec.uri;
    String host = uri.getHost();
    int port = uri.getPort();

    address = InetAddress.getByName(host);
    socketAddress = new InetSocketAddress(address, port);
    if (address.isMulticastAddress()) {
      multicastSocket = new MulticastSocket(socketAddress);
      multicastSocket.joinGroup(address);
      socket = multicastSocket;
    } else {
      socket = new DatagramSocket(socketAddress);
    }

    socket.setSoTimeout(socketTimeoutMillis);

    opened = true;
    if (listener != null) {
      listener.onTransferStart();
    }

    reset();

    return C.LENGTH_UNBOUNDED;
  }

  @Override
  public int read(byte[] buffer, int offset, int readLength) throws IOException {
    while(packetRemaining == 0) {
      if (packetQueue.size() > 0) {
        MrdpPacket out = packetQueue.peek();
        if (lastStreamId < 0) {
          lastStreamId = out.streamId;
        } else if (lastStreamId != out.streamId) {
          throw new IOException("Stream changed");
        }
        boolean first = lastSerialNumber < 0;
        int diffSn = first? 1 : out.serialNumber - lastSerialNumber;
        long now = SystemClock.elapsedRealtime();
        if (first || diffSn == 1 ||
            diffSn < -3 * DEFAULT_MAX_PACKET_COUNT || // maybe a server restart which causes wrap of serialNumber
            (now - lastTime) > DEAFULT_PACKET_TIMEOUT_MILLIS ||
            packetQueue.size() > DEFAULT_MAX_PACKET_COUNT) {
          if (out.payload.length == 0) {
            packetQueue.poll();
          }
          goodPacketCount++;
          if (diffSn > 1) {
            lostPacketCount += (diffSn - 1);
            System.out.println(">>>>MrdpDataSource: LOST " + (diffSn - 1) + " since " + lastSerialNumber);
          }
          packetRemaining = out.payload.length;
          lastSerialNumber = out.serialNumber;
          lastTime = now;
          if (listener != null) {
            listener.onBytesTransferred(packetRemaining);
          }
          if (goodPacketCount == 100) {
            System.out.println(">>>>MrdpDataSource:good=" + goodPacketCount + ",dup or late=" + duplicatedOrLatePacketCount
              + ",lost=" + lostPacketCount + ",lost ratio=" + ((float)lostPacketCount) / goodPacketCount);
            goodPacketCount = lostPacketCount = duplicatedOrLatePacketCount = 0;
          }
        } else if (diffSn <= 0) {
          packetQueue.poll();
          duplicatedOrLatePacketCount++;
        } else {
          receive();
        }
      } else {
        receive();
      }
    }

    MrdpPacket out = packetQueue.peek();
    int packetOffset = out.payload.length - packetRemaining;
    int bytesToRead = Math.min(packetRemaining, readLength);
    System.arraycopy(out.payload, packetOffset, buffer, offset, bytesToRead);
    packetRemaining -= bytesToRead;
    if (packetRemaining == 0) {
      packetQueue.poll();
    }
    return bytesToRead;
  }

  @Override
  public Uri getUri() {
    return uri;
  }

  @Override
  public void close() {
    uri = null;
    if (multicastSocket != null) {
      try {
        multicastSocket.leaveGroup(address);
      } catch (IOException e) {
        // Do nothing.
      }
      multicastSocket = null;
    }
    if (socket != null) {
      socket.close();
      socket = null;
    }
    address = null;
    socketAddress = null;
    if (opened) {
      opened = false;
      if (listener != null) {
        listener.onTransferEnd();
      }
    }
    reset();
  }

  private void reset() {
    packetRemaining = 0;
    lastStreamId = lastSerialNumber = -1;
    lastTime = 0;
    duplicatedOrLatePacketCount = goodPacketCount = lostPacketCount = 0;
    packetQueue.clear();
  }

  private void receive() throws IOException {
    socket.receive(packet);
    packetQueue.add(new MrdpPacket(packet.getData(), packet.getOffset(), packet.getLength()));
  }
}

class MrdpPacket implements Comparable {
  public final int streamId;
  public final int serialNumber;
  public final byte[] payload;
  public MrdpPacket(byte[] src, int offset, int length) {
    if (length < 8) {
      throw new IllegalArgumentException("Too short");
    }
    if (!(src[offset + 0] == 'm' && src[offset + 1] == 'r')) {
      throw new IllegalArgumentException("Bad MRDP signature");
    }
    streamId = (src[offset + 2] & 0xFF) | ((src[offset + 3] & 0xFF) << 8);
    serialNumber = (src[offset + 4] & 0xFF) | ((src[offset + 5] & 0xFF) << 8) |
        ((src[offset + 6] & 0xFF) << 16) | ((src[offset + 7] & 0xFF) << 24);
    if (serialNumber < 0) {
      throw new IllegalArgumentException("Bad serial number");
    }
    payload = new byte[length - 8];
    System.arraycopy(src, offset + 8, payload, 0, payload.length);
  }

  @Override
  public int compareTo(Object another) {
    return this.serialNumber - ((MrdpPacket)another).serialNumber;
  }
}