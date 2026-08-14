package com.yjiyun.chatflows.manager.artifact;
import java.io.IOException; import java.util.Optional;
public interface TaskArtifactStore { void writeSpec(String runId,String spec) throws IOException; void writeMeta(String runId,String metaJson) throws IOException; String readSpec(String runId) throws IOException; String readResult(String runId) throws IOException; default Optional<String> readResultIfExists(String runId)throws IOException{try{return Optional.of(readResult(runId));}catch(IOException e){return Optional.empty();}} }
