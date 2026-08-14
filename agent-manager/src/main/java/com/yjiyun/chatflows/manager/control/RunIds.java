package com.yjiyun.chatflows.manager.control;

import java.util.regex.Pattern;

/** Canonical run identity shared by Matrix, task storage, tracing and PostgreSQL UUID keys. */
public final class RunIds {
  private static final Pattern UUID_V4 = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);
  private RunIds() {}
  public static String requireV4(String value) {
    if (value == null || !UUID_V4.matcher(value).matches()) throw new IllegalArgumentException("run_id must be UUID v4");
    return value;
  }
}
