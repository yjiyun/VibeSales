package com.yjiyun.chatflows.runtime;

/** Debug inspector. Unset or any value other than on is off (production default). */
public final class InspectorFlag {
  private InspectorFlag() {}

  public static boolean enabled() {
    String value = System.getenv("ARTIFACT_INSPECTOR");
    return value != null && "on".equalsIgnoreCase(value.trim());
  }
}
