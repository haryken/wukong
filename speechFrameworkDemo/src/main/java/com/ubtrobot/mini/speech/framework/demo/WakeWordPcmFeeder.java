package com.ubtrobot.mini.speech.framework.demo;

/**
 * Gửi cùng luồng PCM (từ AudioRecord) vào wake word detector để hey mini luôn nhận đúng audio đang gửi lên server.
 */
public interface WakeWordPcmFeeder {
  void feedPcmFrame(byte[] frame);
}
