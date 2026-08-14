package com.yjiyun.chatflows.manager.platform;
import java.io.IOException;
public interface PlatformClient { void apply(String kind, String name, String yaml) throws IOException, InterruptedException; default void check() throws IOException,InterruptedException{} }
