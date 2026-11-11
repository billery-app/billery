package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Upload content detection: the stored type is decided by the bytes, not by the filename. */
class FileUploadControllerTest {

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = (byte) values[i];
    }
    return result;
  }

  @Test
  void recognisesPngJpegAndGif() {
    assertEquals(
        "png",
        FileUploadController.detectImageExtension(bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0)));
    assertEquals("jpg", FileUploadController.detectImageExtension(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
    assertEquals(
        "gif",
        FileUploadController.detectImageExtension(
            "GIF89a....".getBytes(StandardCharsets.US_ASCII)));
    assertEquals(
        "gif",
        FileUploadController.detectImageExtension(
            "GIF87a....".getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  void rejectsActiveSvgPayload() {
    byte[] svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"><script>alert(1)</script></svg>"
            .getBytes(StandardCharsets.UTF_8);
    assertNull(FileUploadController.detectImageExtension(svg));
  }

  @Test
  void rejectsHtmlAndSvgDisguisedAsImage() {
    assertNull(
        FileUploadController.detectImageExtension(
            "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)));
    assertNull(
        FileUploadController.detectImageExtension(
            "﻿<?xml version=\"1.0\"?><svg/>".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsEmptyTruncatedAndPolyglotPrefixes() {
    assertNull(FileUploadController.detectImageExtension(new byte[0]));
    assertNull(FileUploadController.detectImageExtension(bytes(0x89, 'P', 'N')));
    assertNull(FileUploadController.detectImageExtension(bytes(0xFF, 0xD8)));
    assertNull(
        FileUploadController.detectImageExtension("GIF90a".getBytes(StandardCharsets.US_ASCII)));
  }
}
