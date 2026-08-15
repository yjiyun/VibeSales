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
  /** Leader 口语常用前 8 位；完整 UUID 或 `` `xxxxxxxx` `` / 单词边界都算提到本 run。 */
  public static boolean mentionedIn(String body, String runId) {
    if (body == null || body.isBlank() || runId == null || runId.isBlank()) return false;
    if (body.contains(runId)) return true;
    if (runId.length() < 8) return false;
    String prefix = runId.substring(0, 8);
    return body.contains("`"+prefix+"`") || Pattern.compile("\\b"+Pattern.quote(prefix)+"\\b").matcher(body).find();
  }
}
