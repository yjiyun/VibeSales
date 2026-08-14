package com.yjiyun.chatflows.manager.artifact;
import com.yjiyun.chatflows.manager.control.RunIds; import io.minio.*; import io.minio.errors.ErrorResponseException; import java.io.*; import java.nio.charset.StandardCharsets; import java.util.*;
/** Production official-task filesystem over AgentTeams' S3-compatible MinIO. */
public final class MinioTaskArtifactStore implements TaskArtifactStore {
 private final MinioClient minio; private final String bucket,prefix;
 public MinioTaskArtifactStore(String endpoint,String accessKey,String secretKey,String bucket,String prefix){this(MinioClient.builder().endpoint(require(endpoint,"endpoint")).credentials(require(accessKey,"accessKey"),require(secretKey,"secretKey")).build(),require(bucket,"bucket"),prefix);}
 MinioTaskArtifactStore(MinioClient minio,String bucket,String prefix){this.minio=Objects.requireNonNull(minio);this.bucket=require(bucket,"bucket");this.prefix=normalizePrefix(prefix);}
 public void writeSpec(String runId,String spec)throws IOException{put(objectKey(runId,"spec.md"),spec,"text/markdown; charset=utf-8");}
 public void writeMeta(String runId,String meta)throws IOException{put(objectKey(runId,"meta.json"),meta,"application/json");}
 public String readSpec(String runId)throws IOException{return read(objectKey(runId,"spec.md"),"MinIO read spec failed");}
 public String readResult(String runId)throws IOException{return read(objectKey(runId,"result.md"),"MinIO read result failed");}
 public Optional<String> readResultIfExists(String runId)throws IOException{try{return Optional.of(readResult(runId));}catch(IOException e){for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof ErrorResponseException x&&Set.of("NoSuchKey","NoSuchObject","XMinioInvalidObjectName").contains(x.errorResponse().code()))return Optional.empty();throw e;}}
 public boolean bucketExists()throws IOException{try{return minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());}catch(Exception e){throw io("MinIO bucket check failed",e);}}
 public void checkAccess()throws IOException{String object=objectKey(UUID.randomUUID().toString(),"meta.json"),marker="chatflows-manager-health";try{put(object,marker,"application/json");if(!marker.equals(read(object,"MinIO health read failed")))throw new IOException("MinIO health read mismatch");}finally{try{minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(object).build());}catch(Exception e){throw io("MinIO health cleanup failed",e);}}}
 public String objectKey(String runId,String file){return key(prefix,runId,file);}
 public static String key(String prefix,String runId,String file){RunIds.requireV4(runId);if(!file.matches("(?:meta\\.json|spec\\.md|result\\.md)"))throw new IllegalArgumentException("invalid task file");return normalizePrefix(prefix)+"/task-"+runId+"/"+file;}
 private void put(String key,String body,String type)throws IOException{byte[] data=Objects.requireNonNull(body).getBytes(StandardCharsets.UTF_8);try(InputStream in=new ByteArrayInputStream(data)){minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(in,data.length,-1).contentType(type).build());}catch(Exception e){throw io("MinIO write failed",e);}}
 private String read(String key,String message)throws IOException{try(InputStream in=minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw io(message,e);}}
 private static String normalizePrefix(String value){String prefix=require(value,"task prefix").trim();if(!"teams/chatflows-build-team/shared/tasks".equals(prefix))throw new IllegalArgumentException("task prefix must be the fixed TeamHarness object-key prefix teams/chatflows-build-team/shared/tasks");return prefix;}
 private static String require(String value,String name){if(value==null||value.isBlank())throw new IllegalArgumentException(name+" required");return value;}
 private static IOException io(String message,Exception cause){return cause instanceof IOException x?x:new IOException(message,cause);}
}
