package com.company.issueops.service;

import java.io.IOException;

@FunctionalInterface
public interface AiStreamHandler {
  void onDelta(String text) throws IOException;
}
